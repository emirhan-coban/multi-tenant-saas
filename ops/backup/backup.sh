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

pg_dump --dbname="$DATABASE_URL" --format=custom --compress=6 --no-owner --no-privileges \
        --serializable-deferrable --file="$FILE"
sha256sum "$FILE" | awk '{print $1}' > "$FILE.sha256"
psql "$DATABASE_URL" -At -v ON_ERROR_STOP=1 -c "
  select json_build_object(
    'createdAt', now(), 'serverVersion', current_setting('server_version'),
    'flywayVersion', (select max(version::int) from flyway_schema_history where success),
    'tableRowEstimates', (select json_object_agg(relname, n_live_tup) from pg_stat_user_tables))" > "$FILE.meta.json"
echo "Yedek: $FILE ($(du -h "$FILE" | cut -f1)), sha256=$(cat "$FILE.sha256")"
# Saklama: yerel kopyada 14 günden eski yedekler silinir; uzak/şifreli kopya politikası docs/ops/backup-restore.md'dedir.
find "$TARGET" -name 'saasdb-*.dump*' -mtime +14 -delete
