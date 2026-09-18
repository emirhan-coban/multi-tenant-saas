#!/usr/bin/env bash
# Faz 8 — Geri yükleme tatbikatı. Yedeği GEÇİCİ bir veritabanına açar, doğrular ve siler.
# Canlı veritabanına asla yazmaz. Kullanım:
#   ADMIN_URL=postgresql://admin:parola@host:5432/postgres SOURCE_URL=postgresql://...saasdb ./restore-drill.sh yedek.dump
set -euo pipefail
: "${ADMIN_URL:?ADMIN_URL (CREATE DATABASE yetkili bağlantı) tanımlı olmalı}"
: "${SOURCE_URL:?SOURCE_URL (karşılaştırma için kaynak) tanımlı olmalı}"
DUMP="${1:?yedek dosyası verilmeli}"
DRILL_DB="restore_drill_$(date -u +%Y%m%d%H%M%S)"
ADMIN_BASE="${ADMIN_URL%%\?*}"
ADMIN_QUERY=""; [[ "$ADMIN_URL" == *\?* ]] && ADMIN_QUERY="?${ADMIN_URL#*\?}"
DRILL_URL="${ADMIN_BASE%/*}/$DRILL_DB$ADMIN_QUERY"
started=$(date +%s)
cleanup() { psql "$ADMIN_URL" -q -c "drop database if exists $DRILL_DB with (force)" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "1) Bütünlük: sha256"
if [[ -f "$DUMP.sha256" ]]; then
  [[ "$(sha256sum "$DUMP" | awk '{print $1}')" == "$(cat "$DUMP.sha256")" ]] || { echo "SHA-256 UYUŞMUYOR"; exit 2; }
  echo "   tamam"
else
  echo "   UYARI: .sha256 yok"
fi

echo "2) Geçici veritabanına geri yükleme: $DRILL_DB"
psql "$ADMIN_URL" -q -v ON_ERROR_STOP=1 -c "create database $DRILL_DB"
pg_restore --dbname="$DRILL_URL" --no-owner --no-privileges --exit-on-error "$DUMP"

echo "3) Doğrulama"
fail=0
check() { if [[ "$2" == "$3" ]]; then echo "   PASS $1 ($2)"; else echo "   FAIL $1: kaynak=$2 geri_yüklenen=$3"; fail=1; fi; }
q() { psql "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

check "flyway sürümü" "$(q "$SOURCE_URL" "select max(version::int) from flyway_schema_history where success")" \
                      "$(q "$DRILL_URL" "select max(version::int) from flyway_schema_history where success")"
COUNT_SQL="select string_agg(t || '=' || c, ',' order by t) from (select table_name t, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I', table_name), false, true, '')))[1]::text::bigint c from information_schema.tables where table_schema='public' and table_type='BASE TABLE') s"
check "tablo satır sayıları" "$(q "$SOURCE_URL" "$COUNT_SQL")" "$(q "$DRILL_URL" "$COUNT_SQL")"
RLS_SQL="select string_agg(relname || ':' || relrowsecurity || relforcerowsecurity, ',' order by relname) from pg_class where relnamespace='public'::regnamespace and relkind='r' and relrowsecurity"
check "RLS açık/zorunlu tablolar" "$(q "$SOURCE_URL" "$RLS_SQL")" "$(q "$DRILL_URL" "$RLS_SQL")"
check "RLS politika sayısı" "$(q "$SOURCE_URL" "select count(*) from pg_policies where schemaname='public'")" "$(q "$DRILL_URL" "select count(*) from pg_policies where schemaname='public'")"
check "security definer fonksiyonlar" "$(q "$SOURCE_URL" "select string_agg(proname, ',' order by proname) from pg_proc where pronamespace='public'::regnamespace and prosecdef")" \
                                      "$(q "$DRILL_URL" "select string_agg(proname, ',' order by proname) from pg_proc where pronamespace='public'::regnamespace and prosecdef")"
check "tetikleyiciler" "$(q "$SOURCE_URL" "select count(*) from pg_trigger where not tgisinternal")" "$(q "$DRILL_URL" "select count(*) from pg_trigger where not tgisinternal")"

# Tenant izolasyonu superuser ile ölçülemez (RLS'i atlar). Geçici, süper olmayan bir rol ile denenir.
q "$DRILL_URL" "do \$\$ begin if not exists (select 1 from pg_roles where rolname='restore_drill_probe') then create role restore_drill_probe nologin; end if; end \$\$;
  grant usage on schema public to restore_drill_probe; grant select on all tables in schema public to restore_drill_probe;" >/dev/null
ISOLATION=$(psql "$DRILL_URL" -At -v ON_ERROR_STOP=1 <<'SQL'
begin;
set local role restore_drill_probe;
select set_config('app.current_tenant', (select gen_random_uuid())::text, true);
select (select count(*) from booking_requests) + (select count(*) from appointments) + (select count(*) from tenant_customers)
     + (select count(*) from audit_log) + (select count(*) from in_app_notifications);
rollback;
SQL
)
ISOLATION=$(echo "$ISOLATION" | grep -E '^[0-9]+$' | tail -1)
check "RLS: bilinmeyen tenant 0 satır görür" "0" "$ISOLATION"
# Kontrolün boşa geçmediğini göstermek için gerçek bir tenant bağlamında satır görülmeli.
VISIBLE=$(psql "$DRILL_URL" -At -v ON_ERROR_STOP=1 <<'SQL'
begin;
select set_config('app.current_tenant', (select id::text from tenants order by id limit 1), true);
set local role restore_drill_probe;
select (select count(*) from booking_requests) > 0 and (select count(*) from booking_requests) < (select reltuples from pg_class where relname='booking_requests') + 1;
rollback;
SQL
)
VISIBLE=$(echo "$VISIBLE" | grep -E '^(t|f)$' | tail -1)
check "RLS: gerçek tenant yalnızca kendi satırlarını görür" "t" "$VISIBLE"
q "$DRILL_URL" "reassign owned by restore_drill_probe to current_user; drop owned by restore_drill_probe;" >/dev/null
psql "$ADMIN_URL" -q -c "drop role if exists restore_drill_probe" >/dev/null 2>&1 || true

elapsed=$(( $(date +%s) - started ))
echo "4) Süre (RTO ölçümü): ${elapsed}s, yedek boyutu: $(du -h "$DUMP" | cut -f1)"
[[ $fail -eq 0 ]] && echo "SONUÇ: TATBİKAT BAŞARILI" || { echo "SONUÇ: TATBİKAT BAŞARISIZ"; exit 1; }
