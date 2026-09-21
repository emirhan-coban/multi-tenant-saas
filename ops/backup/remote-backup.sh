#!/usr/bin/env bash
# Şifreli uzak yedek: pg_dump üretir, restic ile uzak depoya yollar ve bütünlüğü kontrol eder.
set -euo pipefail
: "${DATABASE_URL:?DATABASE_URL tanımlı olmalı}"
: "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY tanımlı olmalı}"
: "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE tanımlı olmalı}"
command -v restic >/dev/null || { echo "restic gerekli" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${BACKUP_WORKDIR:-$ROOT/backups}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$ROOT/ops/monitoring/textfile}"
mkdir -p "$TARGET" "$METRIC_DIR"

"$ROOT/ops/backup/backup.sh" "$TARGET"
DUMP="$(find "$TARGET" -name 'saasdb-*.dump' -type f -print | sort | tail -1)"
[[ -n "$DUMP" ]] || { echo "Yedek üretilemedi" >&2; exit 2; }

restic snapshots --json >/dev/null 2>&1 || restic init
restic backup "$DUMP" "$DUMP.sha256" "$DUMP.meta.json" --tag saas-postgres
restic check --read-data-subset="${RESTIC_CHECK_SUBSET:-5%}"
restic forget --tag saas-postgres --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune

NOW="$(date +%s)"
TMP="$METRIC_DIR/backup.prom.tmp"
printf '# TYPE saas_backup_last_success_timestamp_seconds gauge\nsaas_backup_last_success_timestamp_seconds %s\n' "$NOW" >"$TMP"
mv "$TMP" "$METRIC_DIR/backup.prom"
echo "Şifreli uzak yedek ve bütünlük kontrolü tamamlandı: $DUMP"
