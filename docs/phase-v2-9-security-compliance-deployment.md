# Faz 9 — Yayın öncesi güvenlik, KVKK ve dağıtım

Bu faz, ürünü gerçek müşterilerin verisiyle çalıştırmadan önce kapatılması gereken
boşlukları kapatır. Üç başlık altında toplanır:

1. **Veritabanı en az yetki** — RLS'in yazılı değil, gerçekten uygulanır olması.
2. **Sırlar, CORS ve güvenlik başlıkları** — üretimde zayıf yapılandırmayla açılmama.
3. **Hesap güvenliği ve KVKK** — şifre sıfırlama, e-posta doğrulama, veri dışa aktarma, hesap silme.

Ayrıca işletme sahibi için özellik anahtarları ekranı, üretim imajları ve CI eklendi.

---

## 9.1 Veritabanı en az yetki

### Sorun

Uygulama, veritabanına şema sahibi (süper kullanıcı) olarak bağlanıyordu. PostgreSQL'de
süper kullanıcılar **her zaman** satır bazlı güvenliği (RLS) atlar — `FORCE ROW LEVEL SECURITY`
bile onları bağlamaz. Yani V2–V18 arasında yazılmış onlarca RLS politikası üretimde
çalışıyor gibi görünüyor ama hiçbirini uygulamıyordu. Kiracı ayrımı yalnızca uygulama
kodundaki `where tenant_id = ?` koşullarına bağlıydı; tek bir eksik koşul ya da SQL
enjeksiyonu tüm işletmelerin verisini açığa çıkarırdı.

### Çözüm

* `ops/db/01-app-role.sh` — `saas_app` rolünü oluşturur: `nosuperuser`, `nocreatedb`,
  `nocreaterole`, **`nobypassrls`**, şemada `create` yetkisi yok. Yeni kurulumda
  `docker-entrypoint-initdb.d` ile kendiliğinden, mevcut veri biriminde `calistir.command`
  ile çalışır; iki durumda da tekrar çalıştırılabilir.
* `alter default privileges` sayesinde Flyway'in ileride ekleyeceği tablolar da otomatik
  yetkilenir — yeni bir göç sonrası "permission denied" sürprizi olmaz.
* Uygulama `saas_app` ile, **Flyway göçleri şema sahibiyle** bağlanır
  (`spring.flyway.user` / `DB_OWNER_USER`).
* `PostgresTestBase` aynı rolü test konteynerinde de kurar: **bütün entegrasyon testleri
  kısıtlı rolle koşar**, dolayısıyla RLS her testte gerçekten sınanır.

### Kısıtlı rolün ortaya çıkardığı gerçek hatalar

Bu değişiklik, üretimde sessizce veya gürültülü biçimde patlayacak dört hatayı görünür kıldı:

| # | Bulgu | Üretimde etkisi | Düzeltme |
|---|-------|-----------------|----------|
| 1 | `tenants` tablosunda yalnızca `SELECT` politikası vardı | Yeni işletme kaydı (register) RLS ihlaliyle **tamamen başarısız olurdu** | `V20`: `tenant_insert` (`with check (true)`) ve `tenant_update` (kendi bağlamı) politikaları |
| 2 | `RateLimitFilter` → `OperationsService.plan()` bağlam kurmadan `tenants` okuyordu | Her kimlikli istek **503 `RATE_LIMIT_UNAVAILABLE`** dönerdi | `plan()` ve `zone()` artık okumadan önce `context(tenant)` çağırır |
| 3 | `TenantAwareDataSource` işlem dışında `SET LOCAL` kullanıyordu — hiçbir etkisi yoktu | Bağlam yalnızca `operations.context()` çağıran servislerde kuruluyordu; çağırmayan altı servis kısıtlı rolde **boş sonuç** dönerdi | Bağlantı havuzdan alınırken `set_config(..., false)`, havuza dönerken temizleme (bağlantı vekili) |
| 4 | Arka plan işleri `select id from tenants` yapıyordu | Kısıtlı rolde hiçbir tenant görünmez; outbox, saklama ve metrik işleri **sessizce hiçbir şey yapmazdı** | `V18`: `ops_tenant_ids()` güvenli fonksiyonu; `OutboxScheduler` ve `DataRetentionService` bunu kullanır |

Ek olarak `V13`'teki `create_default_booking_settings` tetikleyicisi, `booking_settings`
`FORCE RLS` olduğu için kayıt anında çalışamıyordu; tetikleyici artık kendi işlemi için
bağlamı kurup hemen geri alıyor. Kayıtta benzersiz slug üretimi de `tenant_slug_taken()`
güvenli fonksiyonuna taşındı (kısıtlı rol diğer işletmelerin satırlarını göremez).

### Kanıt

`DatabasePrivilegeTests` beş şeyi doğrudan veritabanı üzerinden kanıtlar:

* uygulama rolü süper kullanıcı değil ve `bypassrls` yok;
* bağlam yokken `appointments` **sıfır satır**, bağlam kurulunca yalnızca o işletmenin satırları;
* şema sahibi bakım için tümünü görebiliyor;
* uygulama rolü `create table` yapamıyor (`permission denied`);
* `tenants` doğrudan okunamıyor ama `ops_tenant_ids()` çalışıyor.

---

## 9.2 Sırlar, CORS ve güvenlik başlıkları

* **JWT anahtarı** artık `APP_JWT_SECRET` ile gelir. Depodaki örnek anahtar üretimde
  reddedilir; 32 bayttan kısa anahtar da reddedilir (HS256).
* **CORS**: `allowedOriginPatterns("*")` kaldırıldı. Yalnızca `app.security.allowed-origins`
  listesindeki tam kaynaklar; izinli yöntem ve başlıklar açıkça sayılır, `allowCredentials=false`.
* **Güvenlik başlıkları** (API): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, CSP (`default-src 'none'; frame-ancestors 'none'`),
  `Cross-Origin-Opener-Policy: same-origin`, `Permissions-Policy`, ve https üzerinde HSTS.
* **Ön yüz** (nginx): kendi CSP'si, aynı başlık seti, `server_tokens off`, `/api/` aynı
  kaynaktan vekillenir (tarayıcı için CORS'a hiç gerek kalmaz).
* **Açılış denetimi** (`SecurityStartupCheck`): `app.security.dev-secrets-allowed=false`
  olduğunda şu durumlarda uygulama **açılmaz**:
  eksik/zayıf JWT anahtarı, boş veya joker CORS listesi, http kaynak, boş
  `APP_OPS_METRICS_TOKEN`, kalıcı olmayan push anahtarları, HSTS kapalı,
  ve **RLS'i atlayan veritabanı rolü**. Geliştirmede aynı bulgular uyarı olarak günlüğe yazılır.

---

## 9.3 Hesap güvenliği

### Şifre sıfırlama

* Token 256 bit rastgeledir; veritabanında yalnızca **SHA-256 özeti** saklanır
  (`account_tokens`). Veritabanını okuyabilen biri geçerli bir bağlantı üretemez.
* Bir amaç için aynı anda **tek açık token** bulunur: yeni istek öncekini geçersiz kılar.
* Hesap başına **saatte 5 istek**; aşılırsa `429 TOO_MANY_REQUESTS`.
* "Şifremi unuttum" isteği, e-posta kayıtlı olsun olmasın **aynı 202 yanıtını** verir;
  hesap sayımı yapılamaz. Arayüz de aynı mesajı gösterir.
* Sıfırlama başarılı olduğunda **tüm oturumlar düşer** (refresh token'lar geçersizleşir,
  push abonelikleri kapanır) ve e-posta doğrulanmış sayılır — bağlantıyı kullanan kişi
  adrese erişebiliyordur.
* Yeni şifre kuralı: 8–72 karakter, harf **ve** rakam/işaret.

### E-posta doğrulama

* Bağlantı 48 saat geçerlidir. Doğrulama **zorunlu bir kapı değildir**: hesap doğrulanmadan
  da çalışır, doğrulama yalnızca görünür bir durumdur (`/auth/email/status`).
* Doğrulama e-postası istemek oturum gerektirir; doğrulamanın kendisi token ile kanıtlandığı
  için açıktır.

### Ekranlar

`/sifremi-unuttum`, `/sifre-sifirla`, `/eposta-dogrula` (işletme) ve aynılarının
`/musteri/...` karşılıkları. Bağlantılar e-postadaki adreslerle birebir eşleşir.

---

## 9.4 KVKK

* **Açık rıza**: kayıt anında yürürlükteki aydınlatma metninin sürümü (`app.kvkk.policy-version`)
  ve zamanı hem `users` hem `customer_accounts` üzerinde saklanır. Metin güncellenince eski
  onaylar karışmaz. Kayıt ekranları metne bağlantı verir (`/kvkk`).
* **Veri dışa aktarma**: `GET /api/customer/v1/account/export` müşterinin hesabını,
  işletme ilişkilerini ve randevu geçmişini tek JSON dosyası olarak indirir. Sorgu
  `export_customer_data()` güvenli fonksiyonundadır: yalnızca isteyen hesabın satırları döner.
* **Hesap silme**: `POST /api/customer/v1/account/delete`, gövdede `"HESABIMI SIL"` onayıyla.
  Ad, telefon, e-posta, notlar ve şifre **kalıcı olarak silinir**; randevu satırları
  işletmenin muhasebe kaydı olarak **kimliksiz** kalır (`anonymize_customer_appointments()`).
  Oturumlar düşer, açık token'lar kapanır, `kvkk_requests` tablosuna kişisel veri içermeyen
  bir kanıt kaydı yazılır.
* **Saklama**: kullanılmış/süresi dolmuş hesap token'ları günlük saklama işine eklendi.

> Silmenin neden anonimleştirme olduğu: randevu geçmişi işletmenin ticari ve muhasebe
> kaydıdır; tamamen silinmesi işletmenin yasal saklama yükümlülüğüyle çakışır. Kişisel
> alanlar silindiği için kayıt artık bir kişiye bağlanamaz.

---

## 9.5 Özellik anahtarları ekranı

`/app/feature-flags` — yalnızca işletme sahibi. Her anahtar için ne olduğu, kapatılınca
ne değişeceği ve mevcut durumu yazar. Değişiklik **gerekçe** ister ve işlem geçmişine
yazılır. Global olarak kapatılmış bir anahtar işletme tarafından açılamaz: acil durumda
özelliği kapatan taraf platformdur.

---

## 9.6 Dağıtım

* `backend/Dockerfile` — çok aşamalı; root olmayan kullanıcı; `MaxRAMPercentage`;
  `/actuator/health` ile HEALTHCHECK.
* `web/Dockerfile` + `web/nginx.conf` — Vite derlemesi, güvenlik başlıkları, `/api` vekili,
  parmak izli varlıklar için uzun önbellek, `sw.js` için önbellek yok.
* `docker-compose.prod.yml` — veritabanı ve Redis dışarı açılmaz; sırlar `.env.prod`'dan
  gelir ve **varsayılanı yoktur** (`:?zorunlu`); uygulama `saas_app` ile bağlanır.
* `ops/env.prod.example` — gereken her değişken, nasıl üretileceğiyle birlikte.
* `.github/workflows/ci.yml` — backend testleri, ön yüz derleme + testleri, göç sürüm
  çakışması denetimi ve üretim imajlarının derlenip gerçekten ayağa kalkması.

---

## Doğrulama

| Kontrol | Sonuç |
|---|---|
| `./mvnw test` (kısıtlı DB rolü, gerçek PostgreSQL + Redis) | Faz 9 sonunda tüm testler geçiyor |
| `DatabasePrivilegeTests` | RLS gerçekten uygulanıyor, rol yetkileri kısıtlı |
| `AccountSecurityIntegrationTests` | Sıfırlama, tekrar kullanılamama, sınırlama, doğrulama, KVKK dışa aktarma/silme |
| `npm run build` + `npm test` (web) | Derleme temiz, 119 test geçiyor |
| Yerel çalıştırma (`calistir.command`) | `saas_app` rolü kuruluyor, uygulama kısıtlı rolle açılıyor |

## Bilinçli olarak yapılmayanlar

* **Hesap silme işletme kullanıcıları için yok.** İşletme sahibinin hesabını silmesi
  işletmenin tüm verisini etkiler; bu, müşteri hesabı silmekten farklı bir karardır ve
  ayrı bir "işletme kapatma" akışı ister.
* **E-posta doğrulama zorunlu kapı değil.** Pilot işletmede kaydolan berberin hemen
  çalışmaya başlaması, doğrulanmamış adresten korunmaktan daha önemli görüldü.
* **Gizli anahtar yönetimi (vault) yok.** Sırlar ortam değişkeni olarak verilir; tek
  sunucuda çalışan bir kurulum için yeterlidir, çok sunuculu kuruluma geçilirse
  ayrı bir sır yöneticisi gerekir.
