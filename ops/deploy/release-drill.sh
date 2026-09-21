#!/usr/bin/env bash
# İmaj geri dönüşü ile veri kurtarmayı ayrı kapılar olarak prova eder.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${PREVIOUS_IMAGE_TAG:?PREVIOUS_IMAGE_TAG tanımlı olmalı}"
: "${CURRENT_IMAGE_TAG:?CURRENT_IMAGE_TAG tanımlı olmalı}"

"$ROOT/ops/deploy/rollback.sh" "$PREVIOUS_IMAGE_TAG"
docker compose -f "$ROOT/docker-compose.prod.yml" --env-file "$ROOT/.env.prod" exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"'

export IMAGE_TAG="$CURRENT_IMAGE_TAG"
docker compose -f "$ROOT/docker-compose.prod.yml" --env-file "$ROOT/.env.prod" up -d --no-build
echo "İmaj geri dönüş tatbikatı tamamlandı. Veri kurtarma yalnız restore-drill.sh ile ve geçici DB üzerinde yapılır."
