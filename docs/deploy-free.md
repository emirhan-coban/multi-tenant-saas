# Ücretsiz yayın rehberi (portföy demosu)

Hedef: Supabase (PostgreSQL) + Upstash (Redis) + Cloudflare Workers (web, static assets) + Render (backend).
Hepsinin ücretsiz planı vardır; planlar zamanla değişebilir, kayıt olmadan önce güncel
limitlere bakın. Gerçek sırlar **asla** repoya girmez: `.env.cloud` ve `.env.render` git'te yok sayılır.

> Ücretsiz Render servisi 15 dk hareketsizlikte uyur; ilk istek 30-60 sn sürebilir. Bu bir
> demo kısıtıdır. `keepalive.yml` iş akışı bunu azaltır.

## 1. Supabase (veritabanı)

[`cloud-database.md`](cloud-database.md) bölüm 1-2'yi uygulayın: proje açın, **Session pooler
(5432)** bilgilerini `.env.cloud` içine yazın ve `./ops/cloud/setup-supabase.sh` çalıştırın
(Docker gerekir). Bu, `saas_app` rolünü ve tüm Flyway migration'larını kurar.

## 2. Upstash (Redis)

Bölüm 4: ücretsiz Redis oluşturun, **TLS** (`rediss://`) URL'sini alın.

## 3. Backend (Render)

1. render.com → GitHub ile giriş → **New → Blueprint** → bu repoyu seçin (`render.yaml` okunur).
   Plan `free` olmalıdır; kart istemez.
2. Panelde `sync: false` alanlarını girin:

   | Değişken | Değer |
   | --- | --- |
   | `SPRING_DATASOURCE_URL` | `.env.cloud` içindeki değer (`sslmode=require`, 5432) |
   | `APP_DB_USER` / `APP_DB_PASSWORD` | `saas_app` rolü ve parolası (`.env.cloud`) |
   | `APP_REDIS_URL` | Upstash `rediss://...` |
   | `APP_ALLOWED_ORIGINS` | Frontend adresi, örn. `https://isim.hesap.workers.dev` (https, sonda `/` yok) |
   | `APP_PUBLIC_BASE_URL` | Aynı frontend adresi |
   | `APP_ACCOUNT_MAIL_KEY`, `APP_PUSH_VAPID_PUBLIC_KEY`, `APP_PUSH_VAPID_PRIVATE_KEY`, `APP_PUSH_SUBJECT` | Yerel `.env.render` dosyasından kopyalayın |

   `APP_JWT_SECRET` ve `APP_OPS_METRICS_TOKEN` Render tarafından otomatik üretilir.
3. Deploy bitince `https://<servis>.onrender.com/api/health` `200` dönmelidir.

Flyway uygulamada kapalıdır; şema değişince `setup-supabase.sh` yeniden çalıştırılır.

## 4. Frontend (Cloudflare Workers, static assets)

Cloudflare yeni projeleri Workers olarak açar; `web/wrangler.jsonc` `dist` klasörünü yayınlar ve
bilinmeyen yolları `index.html`'e düşürür (SPA yönlendirmesi).

1. dash.cloudflare.com → **Workers & Pages → Create → Import a repository** → bu repo.
2. **Root directory:** `web` (başında `/` olmadan), **Build command:** `npm run build`,
   **Deploy command:** `npx wrangler deploy`. Worker adı `web/wrangler.jsonc` içindeki `name` ile
   aynı olmalıdır (`multi-tenant-saas`).
3. **Build variables:** `NODE_VERSION=22`, `VITE_API_URL=https://<servis>.onrender.com`,
   `VITE_PUBLIC_APP_URL=https://<worker>.<alt-alan>.workers.dev`.
4. Settings → Domains & Routes → **workers.dev** adresini etkinleştirin.
5. Render'daki `APP_ALLOWED_ORIGINS` ve `APP_PUBLIC_BASE_URL` değerini bu adresle eşitleyin
   (https, sonda `/` yok); aksi halde tarayıcı CORS nedeniyle API'ye erişemez.

> **Güvenlik başlıkları ve CSP:** `web/public/_headers` Cloudflare'de güvenlik başlıklarını (CSP, `X-Frame-Options`,
> `nosniff`, `Referrer-Policy`, `Permissions-Policy`) ve uzun ömürlü önbelleği (`/assets/*`, `/models/*`) ayarlar.
> CSP'nin `connect-src` kısmı API adresini içerir (`https://*.onrender.com`); backend'i başka bir alan adına
> taşırsanız bu dosyayı güncelleyin, aksi halde tarayıcı API isteklerini engeller. Yazı tipleri ve 3D çözücüleri
> kendi paketimizden sunulur, dışarıdan betik/stil yüklenmez.

## 5. Demo verisi

```bash
API_URL=https://<servis>.onrender.com \
DATABASE_URL='postgresql://postgres.<ref>:<owner-parolası>@<host>:5432/postgres?sslmode=require' \
./ops/demo/seed-demo.sh
```

Gerekirse önce `brew install libpq` ile `psql` kurun. Betik tekrar çalıştırılabilir. Demo giriş:
`demo@berber.test` / `Demo1234`; müşteri: `musteri@berber.test` / `Demo1234`;
rezervasyon sayfası: `/book/demo-berber`.

## 6. Keep-alive

Repo → Settings → Secrets and variables → Actions → **New repository secret**:
`DEMO_API_URL` = Render adresi. `keepalive.yml` 14 dakikada bir sağlık ucunu çağırır; bu hem
Render'ı hem Supabase'i (7 gün hareketsizlikte duraklar) uyanık tutar.

## 7. Vitrin

- README'deki `TODO: add URL` yerine canlı adresi yazın.
- `docs/media/` içine ekran görüntüleri (3D koltuk seçimi, sahip paneli, onay kuyruğu) ve kısa
  bir GIF koyup README'deki yorum satırını açın.
- GitHub → repo ana sayfası ⚙ (About): description, website (canlı demo) ve topics:
  `spring-boot`, `multi-tenant`, `postgresql`, `row-level-security`, `react`, `threejs`, `pwa`.

## Bilinçli demo ödünleri

- `APP_SECURITY_DEV_SECRETS_ALLOWED=true`: üretim kipinde canlı Gmail zorunlu olduğundan, demoda
  e-posta kapalıdır ve bu denetim uyarıya düşer. JWT, VAPID ve e-posta anahtarları yine rastgele ve
  güçlüdür. Gerçek müşteri için bu bayrak `false`, `MAIL_DELIVERY_MODE=live` olmalıdır.
- Ödeme (iyzico) yapılandırılmamıştır; plan yükseltme demodur.

## Sık karşılaşılan sorunlar

- **`tenant/user ... not found` (Supabase):** pooler hostu veya proje ref'i yanlış ya da proje
  duraklatılmış. Connect ekranındaki Session pooler değerlerini birebir kullanın.
- **`503 RATE_LIMIT_UNAVAILABLE`:** Upstash Redis'e ulaşılamıyor (veritabanı silinmiş olabilir).
  Yeni veritabanı açıp `APP_REDIS_URL`'yi güncelleyin; adres `rediss://` (TLS) olmalı.
- **Cloudflare `package.json` bulunamadı:** Root directory `web` olmalı, başında `/` olmadan.
- **Frontend açılıyor ama istekler engelleniyor:** Render'da `APP_ALLOWED_ORIGINS` eksik/yanlış.
- **E-posta kapalı olduğu için** demoda *şifre sıfırlama* ve *e-posta doğrulama* çalışmaz (bağlantılar
  gönderilmez). Demo hesaplarının şifresi README'deki tablodadır; unutulursa `ops/demo/seed-demo.sh`
  yeniden çalıştırılır. Personel davetinde bağlantı e-postayla gitmez, panelde **kopyalanabilir
  davet bağlantısı** olarak gösterilir. Gerçek e-posta için [`gmail-email.md`](gmail-email.md).
- HSTS `render.yaml` ile açıktır (`APP_HSTS_MAX_AGE_SECONDS=31536000`). Refresh token'lar tek
  kullanımlıktır; çalıntı/yeniden kullanım tespitinde hesabın tüm oturumları kapatılır.
