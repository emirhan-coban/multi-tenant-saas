#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/docker-compose.prod.yml" --env-file "$ROOT/.env.prod")
set -a
# .env.prod yalnızca işletim ekibinin yönettiği deployment ayarlarıdır.
. "$ROOT/.env.prod"
set +a
: "${IMAGE_TAG:?IMAGE_TAG ortamda tanımlı olmalı}"

cd "$ROOT"
"${COMPOSE[@]}" build
"${COMPOSE[@]}" up -d --no-build

for i in $(seq 1 60); do
  if "${COMPOSE[@]}" ps --status running backend frontend | grep -q 'backend' \
      && "${COMPOSE[@]}" exec -T backend wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
      | grep -q '"status":"UP"'; then
    echo "Dağıtım hazır: IMAGE_TAG=$IMAGE_TAG"
    exit 0
  fi
  sleep 2
done

"${COMPOSE[@]}" ps
"${COMPOSE[@]}" logs --tail 120 migration backend frontend
echo "Dağıtım readiness süresini aştı" >&2
exit 1