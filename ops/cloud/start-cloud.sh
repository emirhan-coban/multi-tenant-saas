#!/usr/bin/env bash
# Backend ve frontend'i Supabase + Upstash ayarlariyla, yerel veri servislerini
# baslatmadan calistirir.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

[[ -f .env ]] || { echo ".env bulunamadi" >&2; exit 2; }
[[ -f .env.cloud ]] || { echo ".env.cloud bulunamadi" >&2; exit 2; }

docker compose --env-file .env --env-file .env.cloud config --quiet
docker compose --env-file .env --env-file .env.cloud --profile app \
  up -d --no-deps backend frontend

echo "Bulut profili baslatildi: frontend=http://localhost:5173 backend=http://localhost:8081"
