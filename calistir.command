#!/bin/bash
# Uygulamayı yerelde (Docker ile) başlatır, demo verisi ekler ve tarayıcıyı açar.
# Finder'da çift tıklayın ya da: ./calistir.command
cd "$(dirname "$0")" || exit 1
LOG="calistir.log"
exec > >(tee "$LOG") 2>&1
pause() { echo; read -r -p "Kapatmak için Enter'a basın..." _; }
fail() { echo; echo "HATA: $1"; echo "Ayrıntı: $LOG"; pause; exit 1; }

echo "== 1/7 Docker kontrol ediliyor"
if ! docker info >/dev/null 2>&1; then
  open -a "Docker Desktop" || fail "Docker Desktop açılamadı."
  for i in $(seq 1 90); do docker info >/dev/null 2>&1 && break; sleep 2; done
  docker info >/dev/null 2>&1 || fail "Docker 3 dakikada hazır olmadı."
fi
[ -f .env ] || cp .env.example .env
set -a; . ./.env; set +a

echo "== 2/7 Postgres ve Redis başlatılıyor"
docker compose up -d postgres redis || fail "docker compose başlatılamadı."
for i in $(seq 1 60); do
  docker exec saas_postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 && break
  sleep 2
done

# Faz 9: uygulama süper kullanıcı olmayan bir rolle bağlanır. Betik yeni kurulumda
# docker-entrypoint-initdb.d ile kendiliğinden çalışır; mevcut veri biriminde ise
# burada elle çalıştırılır (tekrar çalıştırılması güvenlidir).
echo "== 3/7 Uygulama veritabanı rolü hazırlanıyor (saas_app)"
docker exec saas_postgres bash /docker-entrypoint-initdb.d/01-app-role.sh \
  || fail "Uygulama rolü oluşturulamadı."

echo "== 4/7 Backend ve frontend başlatılıyor"
docker compose --profile app up -d || fail "docker compose başlatılamadı."

echo "== 5/7 Backend bekleniyor (ilk açılışta Maven bağımlılıkları iner, 5-10 dk sürebilir)"
for i in $(seq 1 360); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/api/health || true)
  [ "$code" = "200" ] && break
  if [ "$(docker inspect -f '{{.State.Running}}' saas_backend 2>/dev/null)" != "true" ]; then
    docker logs --tail 120 saas_backend; fail "Backend durdu (derleme veya başlangıç hatası, loglar yukarıda)."
  fi
  if docker logs --tail 40 saas_backend 2>&1 | grep -q "BUILD FAILURE\|COMPILATION ERROR\|APPLICATION FAILED TO START"; then
    docker logs --tail 150 saas_backend; fail "Backend derlenemedi/başlamadı (loglar yukarıda)."
  fi
  [ $((i % 15)) -eq 0 ] && echo "   ... hâlâ bekleniyor ($((i*2)) sn) — son log: $(docker logs --tail 1 saas_backend 2>&1 | cut -c1-120)"
  sleep 2
done
[ "$code" = "200" ] || { docker logs --tail 120 saas_backend; fail "Backend 12 dakikada hazır olmadı."; }
echo "   Backend hazır."

echo "== 6/7 Demo verisi"
curl -s -X POST http://localhost:8081/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"demo@berber.test","password":"Demo1234","fullName":"Emre Usta","tenantName":"Demo Berber"}' >/dev/null
curl -s -X POST http://localhost:8081/api/customer/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"musteri@berber.test","password":"Demo1234","fullName":"Ada Müşteri","phone":"+90 555 000 00 00"}' >/dev/null
docker exec -i saas_postgres psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL' || fail "Demo verisi eklenemedi."
do $$
declare t uuid; owner uuid; hash text; u uuid; s uuid; name text; d int;
begin
  select id into t from tenants where slug='demo-berber';
  select id,password_hash into owner,hash from users where email='demo@berber.test';
  if t is null or exists (select 1 from staff where tenant_id=t) then return; end if;
  update tenants set timezone='Europe/Istanbul', plan='PRO' where id=t;
  insert into services(tenant_id,name,duration_min,buffer_min,price) values
    (t,'Saç kesimi',45,0,600),(t,'Sakal tıraşı',30,0,350),(t,'Saç + sakal',60,10,850);
  foreach name in array array['Emre Usta','Ahmet Yılmaz','Can Demir'] loop
    if name = 'Emre Usta' then u := owner;
    else
      insert into users(email,password_hash,full_name) values (lower(split_part(name,' ',1))||'@berber.test',hash,name) returning id into u;
      insert into memberships(user_id,tenant_id,role) values (u,t,'STAFF');
    end if;
    insert into staff(tenant_id,user_id,display_name) values (t,u,name) returning id into s;
    for d in 0..6 loop
      insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (t,s,d,'09:00','20:00');
    end loop;
  end loop;
end $$;
SQL
echo "   Demo işletme: demo-berber (3 berber, 3 hizmet, her gün 09:00-20:00)"

echo "== 7/7 Frontend bekleniyor"
for i in $(seq 1 180); do
  [ "$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/ || true)" = "200" ] && break; sleep 2
done

echo "== Tarayıcı açılıyor"
open "http://localhost:5173/book/demo-berber"
open "http://localhost:5173/login"
cat <<'INFO'

============================================================
 Uygulama çalışıyor
 Müşteri rezervasyon (3D):  http://localhost:5173/book/demo-berber
 Müşteri girişi:           musteri@berber.test / Demo1234
 İşletme paneli:           http://localhost:5173/login
 İşletme sahibi:           demo@berber.test / Demo1234
 Personel:                 ahmet@berber.test, can@berber.test / Demo1234
 API sağlık:               http://localhost:8081/api/health
 Durdurmak için:           durdur.command
 Not: Push için geçici VAPID anahtarı kullanılır (yalnız geliştirme).
============================================================
INFO
pause
