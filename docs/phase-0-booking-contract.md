# Faz 0 - Baslangic ve rezervasyon sozlesmesi

Tarih: 16 Eylul 2026. Bu belge mevcut sistemin olculmus baslangicini ve sonraki
fazlarda uygulanacak musteri rezervasyon sinirini sabitler. Hedef endpointlerin
makinece okunabilir karsiligi `docs/contracts/booking-v1.openapi.yaml` dosyasidir.

## 1. Faz 0 sonucu

| Kontrol | Sonuc | Kanit |
| --- | --- | --- |
| Backend gecici DB | Gecti | Java 21, Testcontainers PostgreSQL 16 + Redis 7: 98 test |
| Frontend test | Gecti | Node 22: 73 test, 9 dosya |
| Frontend production build | Gecti | TypeScript ve Vite build |
| Idempotency | Koruma testi var | Ayni anahtar/ayni govde replay, farkli govde 409, paralel replay |
| Slot yarisi | Koruma testi var | 50 eszamanli istekte tek basari + PostgreSQL exclusion constraint |
| Kota | Koruma testi var | FREE/PRO, aylik sinir ve paralel istekler |
| Fiyat snapshot | Koruma testi var | Hizmet fiyati degisse de tamamlanan randevu cirosu degismiyor |
| RLS policy | Gecti | BYPASSRLS olmayan rol, yanlis/dogru tenant ve transaction sonrasi sifirlama |
| JWT token tipi | Duzeltildi | Refresh token artik korumali API'de access token olarak kullanilamiyor |
| Mobil baslangic | Gecti | 760px kirilimi, mobil menu ve route testleri; build basarili |

Testcontainers Docker icinde calistiriliyorsa Docker Desktop geri baglantisi icin
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` verilmelidir.

## 2. Urun kararlari

Musteri kendi randevusunu web uzerinden alir. Isletme paneli ile musteri uygulamasi
ayri kimlik dogrulama ve route sinirlarina sahiptir. Telefon numarasina tek basina
guvenilmez; musteri hafif bir hesapla oturum acar. Ilk fiziksel ziyaret
`COMPLETED` oldugunda ayni musteri hesabi guvenilir hale gelir.

| Guven durumu | Karar | Talep sonucu | Slot davranisi | Berber islemi |
| --- | --- | --- | --- | --- |
| `NEW` | `REQUIRE_APPROVAL` | `PENDING_APPROVAL` | 15 dakika gecici tutulur | Onay veya red |
| `TRUSTED` | `AUTO_CONFIRM` | `AUTO_CONFIRMED` | Aninda randevuya donusur | Islem gerekmez |
| `REVIEW_REQUIRED` | `REQUIRE_APPROVAL` | `PENDING_APPROVAL` | 15 dakika gecici tutulur | Onay veya red |
| `BLOCKED` | `REJECT` | Talep olusmaz | Tutulmaz | Islem gerekmez |

Durum gecisleri:

- Yeni hesap `NEW` baslar.
- Ilk onayli randevu `COMPLETED` olunca `TRUSTED` olur.
- Bir `NO_SHOW`, odeme/uyusmazlik isareti veya isletmenin manuel karari
  `REVIEW_REQUIRED` yapabilir.
- `BLOCKED` yalnizca OWNER/MANAGER tarafindan verilir ve acik gerekce audit'e yazilir.
- Iptal edilen ya da reddedilen ilk talep guven kazandirmaz.

## 3. Sabit enum ve limitler

Backend kaynaklari `backend/src/main/java/com/saas/backend/booking`, frontend
karsiligi `web/src/api/booking-contract.ts` altindadir.

| Ayar | Varsayilan | Amac |
| --- | ---: | --- |
| Rezervasyon ufku | 30 gun | Cok ileri tarihli sahte bloklari azaltmak |
| Minimum on sure | 60 dakika | Son dakika operasyon baskisini onlemek |
| Onay bekleme/slot tutma | 15 dakika | Berbere kisa onay penceresi verirken takvimi kilitlememek |
| Musteri basina aktif talep | 1 | Bir hesabin takvimi topluca tutmasini engellemek |
| IP basina talep | 5/saat | Ucretsiz ilk savunma; 429 ve `Retry-After` doner |

Enum adlari geriye uyumluluk sozlesmesidir:

- Guven: `NEW`, `TRUSTED`, `REVIEW_REQUIRED`, `BLOCKED`
- Talep: `PENDING_APPROVAL`, `AUTO_CONFIRMED`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED`
- Karar: `REQUIRE_APPROVAL`, `AUTO_CONFIRM`, `REJECT`

## 4. Route ve auth sinirlari

| Alan | Web route | API kok yolu | Kimlik |
| --- | --- | --- | --- |
| Magaza kesfi/3D dukkan | `/book/:tenantSlug` | `/api/public/v1/shops/:tenantSlug` | Anonim, sadece okuma |
| Musteri rezervasyonu | `/customer/*` | `/api/customer/v1/*` | `customer_access` JWT |
| Isletme paneli | `/app/*` | Mevcut `/api/*` | `access` JWT, tenant + rol |
| Talep onayi | `/app/booking-requests` | `/api/booking-requests/*` | OWNER/MANAGER |

Musteri token'i personel endpointlerine, personel token'i musteri hesabina kabul
edilmez. Faz 0'da mevcut staff access/refresh token ayrimi da ayni nedenle zorunlu
hale getirildi. Eksik veya yanlis tip token HTTP 401; gecerli oturumda yetersiz rol
HTTP 403 ve `ApiError` dondurur.

## 5. Endpoint sozlesmesi ozeti

- `GET /api/public/v1/shops/{tenantSlug}`: hizmet, aktif personel, saat dilimi ve
  3D sahnedeki koltukla eslesecek `chairKey`.
- `GET /api/public/v1/shops/{tenantSlug}/availability`: hizmet, personel ve yerel
  tarih icin ISO-8601 UTC slot listesi.
- `POST /api/customer/v1/booking-requests`: customer JWT ve UUID
  `Idempotency-Key` zorunlu. Karar tablosuna gore bekleyen talep veya randevu uretir.
- `GET /api/customer/v1/booking-requests/{id}`: musteri sadece kendi talebini gorur.
- `GET /api/booking-requests?status=PENDING_APPROVAL`: aktif tenant talepleri.
- `POST /api/booking-requests/{id}/approve|reject`: OWNER/MANAGER, idempotent karar.

Tum hata yanitlari `{ "code": string, "message": string, "details"?: object }`
seklindedir. Saatler UTC `date-time`, kullaniciya gosterim tenant timezone ile yapilir.
Onay aninda slot tekrar kontrol edilir; PostgreSQL exclusion constraint son karardir.

## 6. Ekran taslaklari

### NEW

Koltuk secimi -> hizmet -> tarih/saat -> hesap/giris -> "Berber onayi bekleniyor".
Ekranda 15 dakikalik sayac, talep ozeti ve iptal komutu bulunur. Sure dolarsa
`EXPIRED` gosterilir; otomatik tekrar gonderilmez.

### TRUSTED

Ayni secim akisi sonunda "Randevun kesinlesti" gorunur. Tarih, saat, berber,
hizmet ve iptal siniri tek ozet alaninda gosterilir. Personel onayi beklenmez.

### REVIEW_REQUIRED

NEW ile ayni onay ekrani kullanilir; musteriye ceza veya ic risk nedeni aciklanmaz.
Metin yalnizca "Isletme onayi gerekiyor" der. Panelde ise berber karar icin gecmis
tamamlanan, iptal ve gelmeme sayilarini gorur.

Mobilde 3D sahne ust bolumde sabit oranli ve dokunmayla dondurulebilir; koltuk
secildikten sonra erisilebilir liste/form alttan acilir. 3D kullanamayan cihazlar icin
ayni personeller her zaman liste olarak sunulur; rezervasyon 3D'ye bagimli degildir.

## 7. Koruma testleri

Faz 1 ve sonrasi bu testleri silmeden genisletmelidir:

- `AppointmentIntegrationTests`: slot yarisi, idempotency, snapshot, tenant ve RLS.
- `OperationsIntegrationTests`: quota yarisi, fiyat snapshot, yeni tablo RLS.
- `AuthTokenIntegrationTests`: access/refresh ayrimi ve sabit 401 hata govdesi.
- `BookingContractTests`: Java enum ve limit adlari.
- `web/tests/booking-contract.test.ts`: NEW/TRUSTED/REVIEW_REQUIRED fixture uyumu.
- Mevcut frontend route, API transport ve mobil menu testleri.

## 8. Kayitli baslangic riskleri

`P0-DB-01` - Gelistirme compose'u PostgreSQL'e `admin` superuser ile baglanir ve bu
rol RLS'yi bypass eder. Policy'ler kisitli rolle test edilmistir; ancak production
yayini icin Flyway migration rolu ile uygulama runtime rolu ayrilmadan cikis yapilmaz.

`P0-DB-02` - `TenantAwareDataSource` tenant degerini baglanti alinirken `SET LOCAL`
ile yazar. JPA fiziksel baglantiyi transaction baslamadan alirsa deger etkisiz
kalabilir. Yeni rezervasyon modulu her transaction basinda `set_config(..., true)`
calistirmali; mevcut moduller de production rolu gecisinden once ayni modele
alinmalidir.

`P0-WEB-01` - Makinedeki Node 26 kurulumu mevcut Rolldown native optional binding'i
ile acilmadi. Projenin dogrulanmis surumu Node 22'dir; CI ve Docker bu surumu pinlemeli.

Bu uc madde sakli borc degildir: sonraki fazlarin production cikis kapilaridir.
Faz 0 teslimi; calisan baslangic, sabit karar tablosu, tipler, fixture'lar, OpenAPI
sozlesmesi ve kayitli risklerle tamamlanmistir.
