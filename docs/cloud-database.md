# Ucretsiz bulut veritabani: Supabase + Upstash

Bu kurulum mevcut Spring Boot, Flyway ve PostgreSQL RLS tasarimini korur. Supabase
yalnizca PostgreSQL saglar; uygulamanin kendi kimlik sistemi kullanilmaya devam eder.
Redis tabanli rate limit ve dagitik sayaçlar Upstash'a tasinir.

## 1. Supabase projesi

1. Supabase'te yeni bir Free proje acin ve mumkun olan en yakin AB bolgesini secin.
2. Guclu veritabani parolasini parola yoneticisinde saklayin.
3. Dashboard'da **Connect > Session pooler** secin. Port `5432` olan hostu ve project
   ref degerini alin. Transaction pooler (`6543`) kullanmayin; uygulama transaction
   icinde tenant session state'i kullaniyor.
4. `cp ops/env.cloud.example .env.cloud` calistirin ve Supabase alanlarini doldurun.
   `.env.cloud` dosyasini commit etmeyin.

Session pooler IPv4 uzerinden erisilebilir, prepared statement ve session state'i
destekler. JDBC URL mutlaka `sslmode=require` icermelidir.

## 2. Sema, Flyway ve kisitli uygulama rolu

Docker acikken ortam dosyasini yukleyip kurulumu calistirin:

```bash
set -a
source .env.cloud
set +a
./ops/cloud/setup-supabase.sh
```

Betik su islemleri idempotent olarak yapar:

- `saas_app` rolunu `NOSUPERUSER`, `NOCREATEROLE`, `NOBYPASSRLS` ile olusturur;
- Flyway migration'larini `postgres` owner roluyla uygular;
- tablo, sequence ve fonksiyon yetkilerini verir;
- uygulama roluyla baglanip migration sayisini dogrular.

Deployment platformuna `.env.cloud` dosyasini yuklemek yerine degerleri secret olarak
tek tek girin. Ozellikle `SUPABASE_DB_PASSWORD` ve `DB_OWNER_PASSWORD` ayni owner
parolasidir; `APP_DB_PASSWORD` farkli olmalidir. Uygulama runtime'inda Flyway kapaliysa
migration'i release oncesinde bu betikle calistirin.

## 3. Mevcut yerel veriyi tasima

Yerel veritabani calisir haldeyken `.env.cloud` icindeki `LOCAL_DB_PASSWORD` alanini
doldurun ve:

```bash
set -a
source .env.cloud
set +a
./ops/cloud/migrate-local-data.sh
```

Betik hedefte tenant varsa durur; veri ezmez. Yalnizca `public` uygulama verisini gecici
bir dump'a alir; Flyway'in hedefte yeniden olusturdugu migration ve sabit katalog
satirlarini tekrar eklemez. Veriyi tek transaction ile yukler ve tenant/kullanici/randevu
sayilarini karsilastirir. Gecici dump basarili veya basarisiz calisma sonunda silinir.

## 4. Upstash Redis

1. Upstash'ta ayni veya yakin AB bolgesinde bir Free Redis veritabani olusturun.
2. TLS connection string'i alin; `redis://` degil `rediss://` ile baslamalidir.
3. Deployment secret'i olarak `APP_REDIS_URL` alanina girin.

Uygulamanin Lettuce istemcisi URI'dan TLS'i otomatik algilar. Redis gecici rate-limit,
kilit ve sayac verisi tuttugu icin PostgreSQL aktarimindan bagimsizdir.

## 5. Canliya gecis ve geri donus

1. Yerel uygulamayi durdurun; son yazimlardan sonra veri aktarimini calistirin.
2. Bulut ortam degiskenleriyle backend'i baslatin.
3. `/actuator/health/readiness`, giris, tenant ayrimi ve rezervasyon akislarini kontrol edin.
4. En az bir surum boyunca yerel `pgdata` volume'unu silmeyin. Geri donus gerekirse eski
   ortam degiskenleriyle yerel PostgreSQL ve Redis'e baglanin.

Yerel Docker uygulamasini Supabase ve Upstash ile, yerel veri servislerini baslatmadan
calistirmak icin:

```bash
./ops/cloud/start-cloud.sh
```

Normal `docker compose --profile app up` komutu gelistirme varsayilani olarak yerel
PostgreSQL ve Redis'i kullanmaya devam eder. Bulut baglantisi icin yukaridaki betigi
kullanin.

Free Supabase otomatik yedek/PITR saglamaz. Gercek musteri verisi alinmadan once periyodik,
sifreli `pg_dump` yedegi veya ucretli plan karari verilmelidir.
