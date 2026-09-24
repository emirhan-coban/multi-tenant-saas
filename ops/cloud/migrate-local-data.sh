#!/usr/bin/env bash
# Docker Compose'taki mevcut public verisini, semasi setup-supabase.sh ile hazirlanmis
# BOS bir Supabase veritabanina tasir. Hedefte tenant varsa veri ezmemek icin durur.
set -euo pipefail

: "${SUPABASE_DB_HOST:?Supabase session pooler hostu gerekli}"
: "${SUPABASE_PROJECT_REF:?Supabase project ref gerekli}"
: "${SUPABASE_DB_PASSWORD:?Supabase postgres parolasi gerekli}"

SUPABASE_DB_NAME="${SUPABASE_DB_NAME:-postgres}"
SUPABASE_DB_PORT="${SUPABASE_DB_PORT:-5432}"
LOCAL_DB_HOST="${LOCAL_DB_HOST:-host.docker.internal}"
LOCAL_DB_PORT="${LOCAL_DB_PORT:-5432}"
LOCAL_DB_NAME="${LOCAL_DB_NAME:-saasdb}"
LOCAL_DB_USER="${LOCAL_DB_USER:-admin}"
: "${LOCAL_DB_PASSWORD:?Yerel PostgreSQL owner parolasi gerekli}"

OWNER_USER="${SUPABASE_OWNER_USER:-postgres.${SUPABASE_PROJECT_REF}}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
chmod 700 "$TMP_DIR"

command -v docker >/dev/null || { echo "docker gerekli" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "Docker daemon calismiyor" >&2; exit 2; }

# Bu uc tablo Flyway tarafindan hedefte seed edilir. Kimlik ve zaman damgasi disindaki
# icerikleri hedefle ayni degilse sessiz veri kaybi yerine manuel karar iste.
SEED_SQL="select md5(
  coalesce((select jsonb_agg(to_jsonb(x) - array['id','updated_at'] order by flag_key)::text from feature_flags x), '[]') ||
  coalesce((select jsonb_agg(to_jsonb(x) - array['created_at','updated_at','published_at'] order by code, version)::text from legal_documents x), '[]') ||
  coalesce((select jsonb_agg(to_jsonb(x) - array['created_at','updated_at'] order by code)::text from subscription_plans x), '[]')
)"
SOURCE_SEED_HASH="$(docker run --rm -e PGPASSWORD="$LOCAL_DB_PASSWORD" postgres:16-alpine \
  psql --host "$LOCAL_DB_HOST" --port "$LOCAL_DB_PORT" --username "$LOCAL_DB_USER" \
    --dbname "$LOCAL_DB_NAME" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "$SEED_SQL")"
TARGET_SEED_HASH="$(docker run --rm -e PGPASSWORD="$SUPABASE_DB_PASSWORD" postgres:16-alpine \
  psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" --username "$OWNER_USER" \
    --dbname "$SUPABASE_DB_NAME" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "$SEED_SQL")"
[[ "$SOURCE_SEED_HASH" == "$TARGET_SEED_HASH" ]] || {
  echo "Yerel sabit katalog/feature flag verisi varsayilandan farkli; otomatik aktarim durduruldu." >&2
  exit 5
}

TARGET_TENANTS="$(docker run --rm \
  -e PGPASSWORD="$SUPABASE_DB_PASSWORD" postgres:16-alpine \
  psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" --username "$OWNER_USER" \
    --dbname "$SUPABASE_DB_NAME" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command 'select count(*) from public.tenants')"
[[ "$TARGET_TENANTS" == "0" ]] || {
  echo "Hedefte $TARGET_TENANTS tenant var; mevcut veriyi ezmemek icin duruldu." >&2
  exit 3
}

echo "1/3 Yerel verinin gecici, sadece-veri yedegi aliniyor..."
docker run --rm \
  -e PGPASSWORD="$LOCAL_DB_PASSWORD" \
  -v "$TMP_DIR:/backup" \
  postgres:16-alpine \
  pg_dump --host "$LOCAL_DB_HOST" --port "$LOCAL_DB_PORT" \
    --username "$LOCAL_DB_USER" --dbname "$LOCAL_DB_NAME" \
    --format plain --data-only --no-owner --no-privileges \
    --exclude-table-data public.flyway_schema_history \
    --exclude-table-data public.feature_flags \
    --exclude-table-data public.legal_documents \
    --exclude-table-data public.subscription_plans \
    --file /backup/local-data.sql

echo "2/3 Veri tek transaction icinde Supabase'e yukleniyor..."
# Supabase session pooler istemcinin PGOPTIONS degerini oturuma aktarmayabilir.
# Duz SQL dump ve psql kullanarak restore modunu veriyle ayni oturumda, ayni atomik
# transaction icinde etkinlestiririz.
docker run --rm \
  -e PGPASSWORD="$SUPABASE_DB_PASSWORD" \
  -v "$TMP_DIR:/backup:ro" \
  postgres:16-alpine \
  psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" \
    --username "$OWNER_USER" --dbname "$SUPABASE_DB_NAME" \
    --set ON_ERROR_STOP=1 --single-transaction \
    --command "set app.restore_mode = 'on'" \
    --file /backup/local-data.sql

echo "3/3 Temel satir sayilari karsilastiriliyor..."
SOURCE_COUNTS="$(docker run --rm -e PGPASSWORD="$LOCAL_DB_PASSWORD" postgres:16-alpine \
  psql --host "$LOCAL_DB_HOST" --port "$LOCAL_DB_PORT" --username "$LOCAL_DB_USER" \
    --dbname "$LOCAL_DB_NAME" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "select (select count(*) from tenants) || ':' || (select count(*) from users) || ':' || (select count(*) from appointments)")"
TARGET_COUNTS="$(docker run --rm -e PGPASSWORD="$SUPABASE_DB_PASSWORD" postgres:16-alpine \
  psql --host "$SUPABASE_DB_HOST" --port "$SUPABASE_DB_PORT" --username "$OWNER_USER" \
    --dbname "$SUPABASE_DB_NAME" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "select (select count(*) from tenants) || ':' || (select count(*) from users) || ':' || (select count(*) from appointments)")"

[[ "$SOURCE_COUNTS" == "$TARGET_COUNTS" ]] || {
  echo "Dogrulama basarisiz: kaynak=$SOURCE_COUNTS hedef=$TARGET_COUNTS" >&2
  exit 4
}
echo "Veri aktarimi tamamlandi (tenants:users:appointments = $TARGET_COUNTS)."
