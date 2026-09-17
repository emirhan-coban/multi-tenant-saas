# Plan V2 Faz 4 - Dogrulanmis ilk ziyaret ve guven kazanimi

Tarih: 17 Eylul 2026. Bu faz, ilk kez gelen bir musterinin yalnizca randevu
`COMPLETED` yapildi diye otomatik guvenilir olmasini engeller. Guven, musteri
hesabi ile fiziksel ziyaretin kisa omurlu bir kodla eslestirilmesine dayanir.

## 1. Uctan uca akis

1. Musteri, onaylanmis randevusunun iki saat oncesinden bitisinden dort saat
   sonrasina kadar hesabinda `Ziyaret kodu` eylemini kullanir.
2. Backend kriptografik rastgele alti haneli kod uretir. Kod bes dakika gecerlidir;
   veritabaninda acik kod yerine randevu kimligi ve sunucu sirriyla uretilen
   HMAC-SHA256 ozeti tutulur.
3. OWNER/MANAGER randevu detayinda kodu girer. Basarili eslesme dogrulayan kullanici,
   zaman ve randevu kanitini kaydeder. Bes hatali deneme kodu kilitler; musteri yeni
   kod ureterek sayaci sifirlayabilir.
4. Randevu ancak hizmetin `ends_at` zamani gectikten sonra `COMPLETED` veya
   `NO_SHOW` yapilabilir.
5. `NEW` musteri icin hem ziyaret dogrulamasi hem `COMPLETED` sonucu varsa durum
   `TRUSTED` olur. Dogrulama yoksa tamamlanma sayaci artar fakat guven degismez.
6. `REVIEW_REQUIRED` ve `BLOCKED` durumlari otomatik tamamlamayla asılmaz.

## 2. Veri modeli ve guvenlik

- `V12__visit_verification_and_trust_evidence.sql`, `visit_verifications` ve
  `customer_trust_events` tablolarini ekler; ikisinde de zorunlu tenant RLS vardir.
- Kod tek randevuya ve tenant musterisine baglidir. Musteri sadece kendi rezervasyon
  talebinden kod uretebilir; baska hesap ve baska tenant `NOT_FOUND` alir.
- Kullanilmis kod tekrar kullanilamaz. Sure dolumu ve deneme kilidi kalici kaydedilir.
- `tenant_customers` kaydi `trusted_at`, `trusted_by`, `trust_reason` ve
  `evidence_appointment_id` ile guven kararinin nedenini tasir.
- Her otomatik veya manuel durum degisikligi onceki/yeni durum, aktor, gerekce ve
  varsa kanit randevusuyla append-only guven olayina donusur.
- Manuel degisiklikler OWNER/MANAGER ile sinirlidir, gerekce ve optimistic-lock
  `expectedVersion` zorunludur. Eski ekran `STALE_VERSION`/409 alir.

## 3. API

| Yontem | Endpoint | Yetki |
| --- | --- | --- |
| POST | `/api/customer/v1/booking-requests/{id}/visit-code` | Kaydin sahibi musteri |
| POST | `/api/appointments/{id}/visit-verification` | OWNER/MANAGER |
| GET | `/api/booking-customers` | OWNER/MANAGER |
| GET | `/api/booking-customers/{id}/trust-events` | OWNER/MANAGER |
| PATCH | `/api/booking-customers/{id}/trust` | OWNER/MANAGER |

Kod dogrulama hatalari `VISIT_CODE_NOT_FOUND`, `INVALID_VISIT_CODE`,
`VISIT_CODE_EXPIRED` ve `VISIT_CODE_LOCKED` olarak ayristirilir. Tam sozlesme
[`booking-v1.openapi.yaml`](contracts/booking-v1.openapi.yaml) dosyasindadir.

## 4. Frontend

- Musteri hesabindaki onayli randevu, bes dakikalik kodu modal icinde gosterir.
- Panelde bagli musterisi olan kesin randevuda kod girisi ve dogrulama zamani
  gorunur. Kod olmadan randevu tamamlanabilir fakat arayuz bunun guven
  kazandirmayacagini aciklar.
- `/app/customers` dizini tamamlanma/no-show sayaclarini, guven kanitini, gerekceli
  manuel karari ve degismez guven gecmisini birlikte gosterir.
- Musteri listesi ve profil alani dar ekranda tek sutuna iner.

## 5. Test ve cikis kapisi

Backend entegrasyon testleri acik kodun saklanmamasini, bes deneme kilidini, yeni
kodla toparlanmayi, sahiplik ve tenant izolasyonunu, sure dolumunu, bitis zamani
kontrolunu, kanitli guven gecisini, no-show ve manuel optimistic locking'i kapsar.
Frontend testleri kod modalini, randevu dogrulamasini, musteri dizinini ve
gerekceli/surumlu guven istegini kapsar.

Faz; tum backend ve frontend testleri, production build, OpenAPI YAML parse,
`git diff --check` ve masaustu/mobil gorunum kontrolu basarili oldugunda tamamlanir.
