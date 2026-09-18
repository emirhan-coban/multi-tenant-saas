#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${1:?önceki IMAGE_TAG verilmelidir}"
export IMAGE_TAG="$1"

cd "$ROOT"
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
echo "İmaj geri dönüşü tamamlandı: IMAGE_TAG=$IMAGE_TAG"
echo "Bu işlem veritabanını geri almaz; veri kurtarma için docs/ops/backup-restore.md prosedürü uygulanmalıdır."