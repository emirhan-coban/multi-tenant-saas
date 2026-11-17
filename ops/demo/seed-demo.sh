#!/usr/bin/env bash
# Yayınlanmış (veya yerel) bir ortama demo verisi ekler. Tekrar çalıştırmak güvenlidir.
#
# Gerekli ortam değişkenleri:
#   API_URL        örn. https://saas-backend.onrender.com
#   DATABASE_URL   Supabase *owner* (postgres) bağlantısı, Session pooler 5432:
#                  postgresql://postgres.<ref>:<parola>@<host>:5432/postgres?sslmode=require
# İsteğe bağlı: DEMO_PASSWORD (varsayılan Demo1234)
set -euo pipefail
: "${API_URL:?API_URL zorunlu}" "${DATABASE_URL:?DATABASE_URL zorunlu}"
PASS="${DEMO_PASSWORD:-Demo1234}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Backend bekleniyor (soğuk başlangıç 1-2 dk sürebilir)..."
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$API_URL/api/health" || true)" = "200" ] && break
  sleep 5
done

curl -fsS -X POST "$API_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"demo@berber.test\",\"password\":\"$PASS\",\"fullName\":\"Emre Usta\",\"tenantName\":\"Demo Berber\"}" >/dev/null || echo "(sahip hesabı zaten var)"
curl -fsS -X POST "$API_URL/api/customer/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"musteri@berber.test\",\"password\":\"$PASS\",\"fullName\":\"Ada Müşteri\",\"phone\":\"+90 555 000 00 00\"}" >/dev/null || echo "(müşteri hesabı zaten var)"

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -q -f "$HERE/demo-seed.sql"
echo "Tamam. Demo işletme: /book/demo-berber — sahip: demo@berber.test / $PASS"
