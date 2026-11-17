#!/usr/bin/env bash
# Bos bir Supabase projesini mevcut Flyway semasi ve kisitli uygulama roluyle hazirlar.
# Sirlar komut satirina yazilmaz; ortam degiskenlerinden gecici konteynerlere aktarilir.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL="$ROOT/ops/cloud/supabase-bootstrap.sql"

: "${SUPABASE_DB_HOST:?Supabase Connect ekranindaki session pooler hostu gerekli}"
: "${SUPABASE_PROJECT_REF:?Supabase project ref gerekli}"
: "${SUPABASE_DB_PASSWORD:?Supabase postgres parolasi gerekli}"
: "${APP_DB_PASSWORD:?saas_app icin farkli ve guclu bir parola gerekli}"

SUPABASE_DB_NAME="${SUPABASE_DB_NAME:-postgres}"
SUPABASE_DB_PORT="${SUPABASE_DB_PORT:-5432}"
SUPABASE_SSL_MODE="${SUPABASE_SSL_MODE:-require}"
APP_DB_ROLE="${APP_DB_ROLE:-saas_app}"
OWNER_USER="${SUPABASE_OWNER_USER:-postgres.${SUPABASE_PROJECT_REF}}"
APP_POOLER_USER="${SUPABASE_APP_USER:-${APP_DB_ROLE}.${SUPABASE_PROJECT_REF}}"
JDBC_URL="jdbc:postgresql://${SUPABASE_DB_HOST}:${SUPABASE_DB_PORT}/${SUPABASE_DB_NAME}?sslmode=${SUPABASE_SSL_MODE}"

command -v docker >/dev/null || { echo "docker gerekli" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "Docker daemon calismiyor" >&2; exit 2; }

bootstrap_role() {
  docker run --rm \
    -e PGPASSWORD="$SUPABASE_DB_PASSWORD" \
    -v "$SQL:/work/bootstrap.sql:ro" \
    postgres:16-alpine \
    psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" \
      --username "$OWNER_USER" --dbname "$SUPABASE_DB_NAME" \
      --set ON_ERROR_STOP=1 \
      --set "app_role=$APP_DB_ROLE" \
      --set "app_password=$APP_DB_PASSWORD" \
      --file /work/bootstrap.sql
}

echo "1/4 Kisitli uygulama rolu hazirlaniyor..."
bootstrap_role

echo "2/4 Flyway migration imaji olusturuluyor..."
docker build --quiet --target migration --tag saas-migration:cloud "$ROOT/backend" >/dev/null

echo "3/4 Flyway migrationlari uygulanıyor..."
docker run --rm \
  -e FLYWAY_URL="$JDBC_URL" \
  -e FLYWAY_USER="$OWNER_USER" \
  -e FLYWAY_PASSWORD="$SUPABASE_DB_PASSWORD" \
  saas-migration:cloud migrate

# Mevcut tablolara acik grant vermek, betigi daha once migrate edilmis bir projede de
# guvenle tekrar calistirilabilir yapar.
echo "4/4 Yetkiler ve RLS'i atlayamayan uygulama baglantisi dogrulaniyor..."
bootstrap_role >/dev/null
docker run --rm \
  -e PGPASSWORD="$APP_DB_PASSWORD" \
  postgres:16-alpine \
  psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" \
    --username "$APP_POOLER_USER" --dbname "$SUPABASE_DB_NAME" \
    --set ON_ERROR_STOP=1 --tuples-only --no-align \
    --command "select current_user, current_database(), (select count(*) from flyway_schema_history where success);"

echo
echo "Supabase hazir. Uygulama ortam degiskenleri:"
echo "SPRING_DATASOURCE_URL=$JDBC_URL"
echo "APP_DB_USER=$APP_POOLER_USER"
echo "DB_OWNER_USER=$OWNER_USER"
echo "Parolalar bilerek yazdirilmadi. APP_DB_PASSWORD ve DB_OWNER_PASSWORD'u secret olarak girin."
