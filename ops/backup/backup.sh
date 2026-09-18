#!/usr/bin/env bash
# Faz 8 — PostgreSQL mantıksal yedek. Çıktı: sıkıştırılmış custom format dump + SHA-256 + meta.
# Kullanım: DATABASE_URL=postgresql://kullanici:parola@host:5432/saasdb ./backup.sh [hedef_klasör]
set -euo pipefail
: "${DATABASE_URL:?DATABASE_URL tanımlı olmalı}"
TARGET="${1:-./backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$TARGET"
umask 077  # yedek kişisel veri içerir: yalnızca sahibi okuyabilir
FILE="$TARGET/saasdb-$STAMP.dump"

SNAPSHOT_DIR="$(mktemp -d)"
mkfifo "$SNAPSHOT_DIR/in" "$SNAPSHOT_DIR/out"
psql "$DATABASE_URL" -qAt -v ON_ERROR_STOP=1 <"$SNAPSHOT_DIR/in" >"$SNAPSHOT_DIR/out" &
SNAPSHOT_PSQL_PID=$!
exec 3>"$SNAPSHOT_DIR/in"
exec 4<"$SNAPSHOT_DIR/out"
cleanup_snapshot() {
  printf 'rollback;\n' >&3 2>/dev/null || true
  exec 3>&- 2>/dev/null || true
  exec 4<&- 2>/dev/null || true
  wait "$SNAPSHOT_PSQL_PID" 2>/dev/null || true
  rm -rf "$SNAPSHOT_DIR"
}
trap cleanup_snapshot EXIT

printf 'begin; select pg_export_snapshot();\n' >&3
IFS= read -r SNAPSHOT_ID <&4
[[ -n "$SNAPSHOT_ID" ]] || { echo "PostgreSQL snapshot alınamadı" >&2; exit 1; }

pg_dump --dbname="$DATABASE_URL" --format=custom --compress=6 --no-owner --no-privileges \
        --snapshot="$SNAPSHOT_ID" --file="$FILE"
sha256sum "$FILE" | awk '{print $1}' > "$FILE.sha256"
printf "select json_build_object(
  'createdAt', now(), 'serverVersion', current_setting('server_version'),
  'flywayVersion', (select max(version::int) from flyway_schema_history where success),
  'tableRowCounts', (select json_object_agg(table_name, row_count) from (
    select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I', table_name), false, true)))[1]::text::bigint row_count
    from information_schema.tables where table_schema='public' and table_type='BASE TABLE'
  ) counts))\n" >&3
IFS= read -r MANIFEST <&4
printf '%s\n' "$MANIFEST" > "$FILE.meta.json"
cleanup_snapshot
trap - EXIT
echo "Yedek: $FILE ($(du -h "$FILE" | cut -f1)), sha256=$(cat "$FILE.sha256")"
# Saklama: yerel kopyada 14 günden eski yedekler silinir; uzak/şifreli kopya politikası docs/ops/backup-restore.md'dedir.
find "$TARGET" -name 'saasdb-*.dump*' -mtime +14 -delete
