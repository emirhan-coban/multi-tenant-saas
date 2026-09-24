# Multi-tenant SaaS

## Faz 0 - Musteri rezervasyon sozlesmesi

Ilk kez gelen musterinin berber onayina, guvenilir musterinin otomatik onaya
gittigi yeni rezervasyon akisinin karar tablosu, auth sinirlari, sabit enum/limitleri
ve test edilmis baslangic raporu [Faz 0 teknik notlarinda](docs/phase-0-booking-contract.md).
Makinece okunabilir hedef API: [booking-v1.openapi.yaml](docs/contracts/booking-v1.openapi.yaml).

## Faz 1 - Musteri kimligi ve veri temeli

Ayri musteri access/refresh tokenlari, kayit/giris/oturum yenileme, tenant bazli
guven profili, booking request veri modeli ve `/customer` web alani tamamlandi.
Mimari ve test kanitlari: [Faz 1 teknik notlari](docs/phase-1-customer-foundation.md).

## Faz 2 - 3D musteri rezervasyonu

Public dukkan ve musaitlik API'leri, Three.js koltuk secimi, musteri rezervasyon
talebi, degerlendirme suresi, guvenilir musteri icin otomatik randevu,
idempotency ve Redis IP limiti tamamlandi. Akis, concurrency kararlari ve test
kanitlari: [Faz 2 teknik notlari](docs/phase-2-customer-booking.md).

## Faz 3 - Berber onayi ve musteri guveni

OWNER/MANAGER onay kuyrugu, atomik ve idempotent onay/red, talep suresi
dolumu, audit/outbox, musteri sonuc takibi ve tamamlandi/no-show tabanli guven
gecisleri tamamlandi. Kurallar ve test kanitlari:
[Faz 3 teknik notlari](docs/phase-3-booking-approval.md).

## Plan V2 Faz 4 - Dogrulanmis ilk ziyaret

Musterinin hesabindan uretilen bes dakikalik ziyaret kodu, kasada OWNER/MANAGER
tarafindan dogrulanir. Ilk randevunun tamamlanmasi ancak bu kanit varsa musteriyi
`TRUSTED` yapar; sonraki rezervasyonlar otomatik kesinlesebilir. Kod guvenligi,
guven gecmisi ve musteri yonetim ekrani:
[Faz V2-4 teknik notlari](docs/phase-v2-4-visit-verification.md).

## Plan V2 Faz 5 - Guvenilir musteride otomatik onay

Kanitli ilk ziyaretten sonra `TRUSTED` musteri, isletme politikasi aciksa ikinci
ve sonraki randevularini yonetici islemi olmadan kesinlestirir. Gelecek randevu,
gunluk islem ve musteri zaman cakismasi limitleri ile guven iptali yarisi sunucuda
korunur. Uygulama ve test ayrintilari:
[Faz V2-5 teknik notlari](docs/phase-v2-5-auto-confirmation.md).

## Plan V2 Faz 6 - Rezervasyon operasyonlari

Surum kontrollu self servis iptal ve tasima, TRUSTED musteride atomik tasima,
diger musterilerde eski randevuyu koruyan onay akisi, mola/kapali gun yonetimi,
tenant rezervasyon ayarlari, uygulama ici bildirimler, `.ics` indirme ve
ayristirilmis talep/iptal/ziyaret raporlari tamamlandi. Uygulama ve test
ayrintilari: [Faz V2-6 teknik notlari](docs/phase-v2-6-booking-operations.md).

## Plan V2 Faz 7 - Surumlu 3D dukkan

Tenant bazli taslak/yayin/rollback sahne yonetimi, koltuk-personel eslemesi,
public sahne manifesti, kayit aninda eski esleme kontrolu ve `WEB_3D` randevu
kaynagi tamamlandi. Musteri Liste/3D arasinda secim taslagini kaybetmeden
gecebilir; OWNER/MANAGER `/app/shop-design` ekranindan yayindaki dukkan
duzenini yonetir. Mimari, API ve test kapisi:
[Faz V2-7 teknik notlari](docs/phase-v2-7-versioned-3d-shop.md).

## Musteri rezervasyonu Faz 4 - Hesap ve self servis iptal

Musteri hesabinda tenantlar arasi randevu gecmisi, durum ozetleri, canli talep
sayaci ve tenant ayarindan okunan (varsayilan 120 dakika) sinira tabi idempotent
iptal tamamlandi. Veri modeli,
concurrency kurallari ve test kapsami:
[Faz 4 teknik notlari](docs/phase-4-customer-self-service.md).

## Docker ile tüm uygulamayı başlatma

Mevcut `.env` dosyanızı koruyun. İlk kurulumda `.env.example` değerleriyle `.env` oluşturun.

```bash
docker compose --profile app up -d
```

Frontend: http://localhost:5173/app — Backend health: http://localhost:8081/api/health.
Bu geliştirme profili Postgres, Redis, backend ve frontend'i birlikte çalıştırır; yerel Java/Node gerektirmez.

## Ücretsiz bulut veritabanı

Yerel PostgreSQL/Redis yerine Supabase Free ve Upstash Redis kullanmak için güvenli rol
kurulumu, Flyway migration'ı, mevcut veri aktarımı ve geri dönüş adımları
[bulut veritabanı rehberinde](docs/cloud-database.md) yer alır. Gerçek bağlantı bilgileri
yalnızca `.env.cloud` içinde tutulur ve Git'e eklenmez.

## Faz 4 — Operasyon ve raporlama

Gmail ile gerçek müşteri e-postası desteği eklendi (varsayılan kapalı, güvenli test modu). `.env.mail` yerel ayarlarını ve test adımlarını [Gmail kurulum notlarında](docs/gmail-email.md) bulabilirsiniz. Müşteri e-postaları, önceki konsol hatırlatmalarından ayrı çalışır.

FREE/PRO kotaları, Redis/Bucket4j istek sınırlaması, immutable audit, JSON loglar, ShedLock korumalı transactional outbox, bildirim tercihleri, ciro/doluluk raporları ve kullanım ekranları eklendi. Bildirim konsola gider; plan yükseltme demo olup ödeme almaz.

API sözleşmeleri, concurrency kararları, rapor tanımları, test komutları ve üretim sınırları: [Faz 4 teknik notları](docs/phase-4-operations.md).

## Web uygulaması

PostgreSQL'i başlatıp backend'i çalıştırdıktan sonra:

```bash
cd web
npm install
npm run dev
```

Web istemcisi varsayılan olarak `http://localhost:8080` adresindeki API'ye bağlanır. Farklı bir API adresi için `VITE_API_URL` kullanılabilir.

Kayıt ve giriş ekranları, tenant üyeliklerini ve token yenilemeyi içerir. Access token bellekte tutulur; refresh token tarayıcı depolamasında kalır ve süresi dolan access token'lar Axios interceptor'ındaki tekil refresh kilidiyle yenilenir. Aynı anda gelen 401 yanıtları tek refresh isteğini bekler.
## Web veri ve yönlendirme altyapısı

- `/` → `/app`; `/login` giriş/kayıt ekranı, `/app` doğrulanmış oturum gerektiren kontrol panelidir. Bilinmeyen adreslerde 404 ekranı gösterilir.
- Aktif tenant ve rol `/api/me` üzerinden doğrulanır. İlk üyeliğin aktif tenant olduğu varsayılmaz. İşletme değiştirme arayüzü henüz yoktur.
- `web/src/query/client.ts`: ortak QueryClient, 30 saniye stale time, 5 dakika cache süresi. Okumalar geçici hatalarda en fazla iki kez tekrar edilir; 4xx ve mutation istekleri otomatik tekrarlanmaz.
- `web/src/query/keys.ts`: tenant bazlı sorgu anahtarları; çalışma saatleri ayrıca personel kimliğiyle ayrılır. Tenant anahtarı yalnızca cache ayrımı içindir; backend tenant'ı access token üzerinden belirler.
- `web/src/api/management.ts`: API fonksiyonları. `web/src/query/management.ts`: ekranlarda kullanılacak query/mutation hook'ları. Bu hook'lar doğrulanmış `TenantProvider` altında kullanılmalıdır.
- Query AbortSignal Axios'a aktarılır. Çıkış, bekleyen HTTP isteklerini (mutation ve refresh dahil) iptal eder ve tüm Query/Mutation cache'ini temizler. HTTP iptali, sunucunun önceden tamamladığı işlemi geri almaz.
- Davet hook'u geçici şifreyi callback üzerinden forma aktarır; mutation sonucunda saklamaz. Form sonuç kapandığında kendi yerel verisini temizlemelidir.
- Hizmet ekleme listede geçici kayıt gösterir; başarıda sunucu kaydıyla değiştirilir, hatada sadece ilgili geçici kayıt kaldırılır. Arka plan yenilemeleri bekleyen eklemeleri korur. Düzenleme ve silme başarıda cache'i günceller ve listeyi yeniden doğrular.

Kontroller:

```bash
cd web
npm test
npm run build
```

Production sunucusu, API ve statik dosya istekleri dışında SPA adreslerini (`/app`, `/login` vb.) `index.html` dosyasına yönlendirmelidir; doğrudan bağlantı ve sayfa yenileme buna bağlıdır. Vite geliştirme sunucusu bunu sağlar.

## Rol bazlı web paneli

Masaüstünde sol menü, 760px ve altında açılır menü kullanılır. Menü ve route guard aynı rol tanımlarına dayanır (`web/src/router/navigation.ts`). Rol, `/api/me` yanıtından alınır; tanınmayan roller panele alınmaz. Backend yetkilendirmesi bağımsız olarak korunur.

| Adres | Sayfa | Roller |
| --- | --- | --- |
| `/app` | Genel Bakış | OWNER, MANAGER, STAFF |
| `/app/booking-link` | Rezervasyon Bağlantım | OWNER, MANAGER, STAFF |
| `/app/services` | Hizmetler | OWNER, MANAGER |
| `/app/staff` | Personel | OWNER, MANAGER |
| `/app/working-hours` | Çalışma Saatleri | OWNER, MANAGER |
| `/app/appointments` | Randevular | OWNER, MANAGER |
| `/app/booking-requests` | Onay Bekleyenler | OWNER, MANAGER |
| `/app/booking-settings` | Rezervasyon Ayarlari | OWNER, MANAGER |
| `/app/shop-design` | 3D Dukkan Tasarimi | OWNER, MANAGER |
| `/app/account` | Hesabım | OWNER, MANAGER, STAFF |

Hesabım mevcut üyelikleri ve aktif çalışma alanını görüntüler.

## Faz 5 — Rezervasyon bağlantısını paylaşma

`/app/booking-link`, access token'daki doğrulanmış tenant için `/book/{tenantSlug}` adresini üretir. Bağlantı kopyalanabilir, hazır WhatsApp mesajıyla paylaşılabilir, QR PNG olarak indirilebilir veya A4 afiş halinde yazdırılabilir. QR tarayıcıda üretilir ve üçüncü taraf servise veri göndermez. Production adresi `VITE_PUBLIC_APP_URL` ile belirlenir; ayrıntılar ve güvenlik sınırları [Faz 5 teknik notlarında](docs/phase-5-booking-link-sharing.md) yer alır.

## Hizmetler ekranı

`/app/services` OWNER/MANAGER için liste, ekleme/düzenleme formu ve silme onayı içerir. Fiyatlar TRY olarak gösterilir; girişte nokta ve virgül ondalık ayracı kabul edilir. İlk yüklemede skeleton, boş listede ekleme çağrısı ve hatada tekrar deneme sunulur. Arka plan hatalarında mevcut liste korunur.

Ekleme sırasında modal kapanır ve listede “Kaydediliyor…” satırı belirir. Başarısız ekleme kaldırılır; aynı ekranda form taslağı tekrar denemek için tutulur. Taslak kalıcı depolamaya yazılmaz. Düzenleme/silme hatalarında modal açık kalır. Native dialog klavye odağını sınırlar; Escape ile kapanır ve odağı açan düğmeye döndürür (işlem sürerken kapanma engellenir).

## Personel ekranı

`/app/staff` OWNER/MANAGER için personel adı ve aktiflik durumunu listeler. Davet formu ad soyad ve e-posta alır; boş/geçersiz alanları ve bekleyen işlemin tekrar gönderilmesini engeller. Hatalarda form bilgileri korunur. İlk yüklemede skeleton, boş listede davet çağrısı ve liste hatasında tekrar deneme vardır.

`POST /api/staff/invite` mevcut backend davranışıyla personeli doğrudan oluşturur; e-posta göndermez. Sonuç penceresi e-postayı ve varsa geçici şifreyi gösterir. Mevcut hesaplar için şifre dönmediğinde mevcut şifreyle giriş bilgisi gösterilir. Kopyalama başarısızsa şifre alanından elle kopyalanabilir.

Geçici şifre yalnızca sonuç penceresinin yerel state'inde tutulur; Query/Mutation sonucuna, localStorage veya sessionStorage'a yazılmaz. Sonuç kapandığında veya sayfadan ayrılınca temizlenir. Mutasyon otomatik tekrar edilmez; başarılı oluşturma sonrasında liste yenilenir. Liste yenileme hatası oluşturma sonucunu silmez.

## Haftalık çalışma saatleri

`/app/working-hours`: personel seçimi, yedi gün için açık/kapalı ve saat seçicileri, seçilen günün saatlerini Pazartesi–Cuma'ya uygulama, tek kaydetme işlemi. Kapalı günlerde saatler gönderilmez. Kaydedilmemiş form arka plan yenilemelerinden korunur; personel değişiminde ve uygulama içi sayfa geçişlerinde onay istenir. Sayfa yenileme/kapatma tarayıcının yerel uyarısını kullanır. Kaydetme sırasında personel seçimi ve tekrar gönderim engellenir. Yönlendirme, bu kontroller için React Router data router üzerinden çalışır.

- `GET /api/working-hours?personelId=<uuid>`: aktif tenant içindeki personelin kayıtları.
- `PUT /api/working-hours`: tüm haftayı tek transaction içinde değiştirir; OWNER/MANAGER yetkisi gerekir.
- Gün sözleşmesi: **0=Pazartesi, 1=Salı, …, 6=Pazar**. `days` tam yedi benzersiz gün içermelidir.
- Açık gün: `closed: false`, `startTime` ve `endTime` zorunlu, başlangıç bitişten önce olmalı. Gece yarısını aşan aralıklar bu sürümde desteklenmez.
- Kapalı gün: `closed: true`, her iki saat `null`. Veritabanında o güne ait kayıt tutulmaz.
- Örnek gövde şekli: `{ "personelId": "<uuid>", "days": [{ "dayOfWeek": 0, "closed": false, "startTime": "09:00", "endTime": "18:00" }, ...diğer altı gün] }`.
- Yanıt, yalnızca açık günleri içeren mevcut `CalismaSaatiResponse[]` biçimindedir.
- Personel satırı üzerinde kilit, aynı personelin POST/PUT işlemlerini sıraya alır. Tekrar PUT, eski haftayı değiştirir ve kayıt çoğaltmaz. Eski POST endpoint'i, kayıtlı bir güne ikinci aralık eklemeyi reddeder (`DAY_ALREADY_EXISTS`).
- Önceden aynı güne birden fazla aralık girilmişse web ekranı sessizce aralık silmek yerine düzenlemeyi engeller. Eski veriler otomatik dönüştürülmez; haftalık PUT ile açıkça gönderilen plan eski aralıkları değiştirir.

PostgreSQL entegrasyon testleri Testcontainers ile otomatik oluşturulan ayrı, geçici bir veritabanında çalışır. Docker açık ve Java 21 kurulu olmalıdır:

```bash
./backend/mvnw -f backend/pom.xml test
```

Entegrasyon senaryoları tekrar kayıt, tüm günleri kapatma, bozuk hafta/saat doğrulaması, veritabanı hatasında rollback, eşzamanlı kayıt, tenant ayrımı ve HTTP rol kontrolünü kapsar. `HOURS_INTEGRATION` artık gerekmez; `contextLoads` dahil tüm Spring testleri geçici DB kullanır.

## Faz 3 — Randevular

### Takvim arayüzü

- Gün görünümünde personel sütunları, hafta görünümünde Pazartesi–Pazar sütunları ve renkli randevu blokları bulunur. Çizelge 09:00–18:00 ile başlar; dışarıdaki randevular için genişler.
- Üstte Hepsi/personel sekmeleri, önceki/sonraki dönem ve Bugün kontrolleri vardır.
- Yeni randevu penceresi hizmet → personel → tarih → saat butonları sırasını izler. Boş günde sonraki 30 günü tarayan, durdurulabilir en yakın gün araması vardır.
- Randevuya tıklayınca müşteri, telefon, hizmet, personel, not ve durum bilgileri açılır. Not düzenleme, tamamlandı/gelmedi/iptal ve taşıma desteklenir.
- Masaüstünde bloğu aynı personelin başka saatine veya hafta görünümünde başka güne sürükleyin. Backend'in sunduğu en yakın müsait saat onay penceresinde gösterilir. Personel değiştirme sürükleme ile yapılmaz. Mobil/klavye için detay penceresindeki Taşı aynı işlemi sağlar.
- TanStack Query mutation, randevuyu hemen soluk/pending blok olarak takvime ekler. Başarıda sunucu kaydıyla değiştirir; hatada yalnızca ilgili değişikliği geri alır. Aynı gövdeyle ağ hatası tekrarı aynı Idempotency-Key'i korur.
- SLOT_UNAVAILABLE / 409 açık bir saat doldu mesajı gösterir; müsaitlik ve takvim otomatik yenilenir. Eski sürüm çakışmaları ayrı mesajla gösterilir.
- Takvim tarayıcının saat dilimini, müsaitlik butonları işletmenin saat dilimini açıkça gösterir.

Son frontend doğrulaması: 53 test geçti (15 takvim/randevu testi dahil), production build başarılı. Ek takvim veya sürükleme kütüphanesi kullanılmadı.

`/app/appointments` sayfası müsait saat seçimi, oluşturma, günlük liste/personel filtresi, taşıma ve durum değişiklikleri sunar. PostgreSQL exclusion constraint tampon dahil çakışmayı engeller. Idempotency-Key yinelenen oluşturmayı, @Version eşzamanlı düzenlemede veri kaybını önler.

API örnekleri, tasarım kararları, saat dilimi sözleşmesi, kapsam sınırları ve Java kurmadan Docker ile test komutları: [Faz 3 teknik notları](docs/phase-3-appointments.md).

## Plan V2 Faz 8 - PWA, bildirim, performans ve kontrollü yayın

Web push (RFC 8291/8292, harici kütüphanesiz), güvenli service worker ve sınırlı çevrimdışı kabuk,
ana ekrana ekleme, feature flag kill switch'leri (`booking.3d`, `booking.trusted_auto_confirm`,
`push.notifications`, `pwa.install`), operasyon metrikleri, tüm cihazlardan çıkış, veri saklama işi,
backup/restore tatbikatı ve erişilebilirlik/zoom/düşük donanım denetimi. Uçtan uca güven senaryosu:
[phase-8/e2e-trust-lifecycle.md](docs/phase-8/e2e-trust-lifecycle.md). Genel belge ve doğrulama durumu:
[Faz V2-8 teknik notları](docs/phase-v2-8-pwa-notifications-rollout.md).

## Plan V2 Faz 9 - Yayın öncesi güvenlik, KVKK ve dağıtım

Uygulama artık veritabanına **süper kullanıcı olmayan** `saas_app` rolüyle bağlanır; RLS
yazılı değil, gerçekten uygulanır (Flyway göçleri şema sahibiyle çalışır). Bütün entegrasyon
testleri de bu kısıtlı rolle koşar. Sırlar ortam değişkenlerine taşındı, CORS'taki joker
kaldırıldı, güvenlik başlıkları ve açılış denetimi eklendi. Şifre sıfırlama ve e-posta
doğrulama (tek kullanımlık, özeti saklanan token'lar), KVKK açık rıza kaydı, veri dışa
aktarma ve hesap silme (anonimleştirme) devrede. İşletme sahibi için özellik anahtarları
ekranı, üretim imajları (`docker-compose.prod.yml`, `backend/Dockerfile`, `web/Dockerfile`)
ve GitHub Actions CI eklendi. Ayrıntılar ve kısıtlı rolün ortaya çıkardığı dört üretim hatası:
[Faz V2-9 teknik notları](docs/phase-v2-9-security-compliance-deployment.md).

Üretim kurulumu için: `cp ops/env.prod.example .env.prod` (değerleri doldurun), ardından
`docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`.

## Plan V2 Faz 10 - Telefonda aşamalı rezervasyon akışı

Telefonda akış aşamalara ayrıldı: **berber seçimi** (sahne + hizmet çipleri + berber şeridi),
**saat** (sahne sökülür, yerine seçim anında yakalanan donmuş kare gelir), **bilgiler** ve
**son kontrol**. Berber isimleri 3B sahneden çıkarılıp şeride taşındı (uzun isimler koltukta
çakışıyordu); koltukta yalnızca numara kalır. Alt panel sürüklenebilir ama genişlet/küçült
düğmesi de var; fiyat ve birincil düğme altta sabit kalır ve klavye açıldığında hiçbir alanı
örtmez. Tüm dokunma hedefleri 48 CSS piksel. Sahne görünmezken render durur, mobilde
yansıma/gölge kapalıdır, DPR sınırlıdır. Ölçüm: LCP, INP ve kare bütçesinin yanında sahnenin
**seçilebilir olma süresi** ayrıca toplanır (`POST /api/public/v1/metrics/booking`).
Ayrıntılar: [Faz V2-10 teknik notları](docs/phase-v2-10-mobile-booking-flow.md).
