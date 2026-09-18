# Plan V2 Faz 5 - Ikinci ve sonraki randevularda otomatik onay

Tarih: 17 Eylul 2026. Bu faz, Faz V2-4'te kanitli ziyaretle `TRUSTED` olan
musterinin ayni hesapla sonraki bos saati yonetici islemi olmadan almasini tamamlar.

## 1. Karar akisi

1. Idempotency replay her kontrolden once aranir. Ayni anahtar ve govde daha once
   sonuc uretti ise guncel guven degisse bile ayni kayit doner; ikinci kayit acilmaz.
2. Musteri profili yazma kilidiyle okunur. `BLOCKED` reddedilir.
3. Tenant'in `trusted_auto_confirm_enabled` politikasi ve guncel `trust_state`
   birlikte degerlendirilir.
4. `TRUSTED` ve politika aciksa sunucu limitleri/musaitligi kontrol edip ortak
   appointment factory ile kesin `CONFIRMED` randevu olusturur.
5. Diger durumlarda sonuc bir `REQUEST` olur ve OWNER/MANAGER onayi bekler.
6. API her zaman `resultType=REQUEST|APPOINTMENT` doner. Istemci guven etiketinden
   sonuc tahmin etmez.

## 2. Limitler ve yarıs guvenligi

- Guvenilir musteri basina varsayilan gelecek kesin randevu limiti: 2.
- Musteri basina tenant saat diliminde varsayilan gunluk yeni booking islemi: 3.
- Ayni musteri farkli berberleri secerek cakisan kesin randevu alamaz.
- Personel+tarih advisory lock ve PostgreSQL appointment exclusion constraint'i
  ayni koltuk icin tek kesin randevuyu garanti eder.
- Guven degisikligi ve booking ayni `tenant_customers` satir kilidini kullanir.
  Revoke once commit ederse yeni islem talebe duser; appointment once commit ederse
  mevcut kesin randevu korunur ve sonraki islemler otomatik onaylanmaz.
- Bekleyen yeni musteri talepleri takvimi bloke etmez. Birden fazla talep ayni saati
  isteyebilir; kesin randevu olusurken musaitlik yeniden kontrol edilir.

## 3. Veri ve API

`V13__trusted_auto_confirmation_policy.sql`, RLS korumali `booking_settings`
tablosunu ekler. Yeni tenant icin varsayilan satir trigger ile uretilir. Alanlar:

- `trusted_auto_confirm_enabled`
- `max_future_appointments`
- `max_daily_bookings`
- `version`

Rezervasyon POST cevabina `resultType` eklenmistir. `APPOINTMENT`, kesin randevu
kimligiyle; `REQUEST`, onay bekleyen talep kimligiyle doner. Trusted replay, iptal
edilmis olsa bile eski kaydi geri dondurur ve yeni appointment yaratmaz.

## 4. Frontend

- Checkout komutu `Randevuyu onayla` olarak sunulur; kesin sonuc optimistic
  gosterilmez.
- `APPOINTMENT/AUTO_CONFIRMED` sonucunda dogrulanmis hesap sayesinde otomatik
  onay acikca belirtilir.
- `REQUEST/PENDING_APPROVAL` sonucunda saatin ayrilmadigi ve kesinlesmenin isletme
  onayina bagli oldugu acikca yazilir.
- Sunucu guveni veya politikayi form acikken degistirirse istemci donen gercek
  sonuc tipini gosterir.

## 5. Test kapsami

Entegrasyon testleri otomatik appointment ve snapshot, politika fallback'i,
bekleyen taleplerin slotu tutmamasi, iki gelecek randevu limiti, gunluk limit,
farkli berberde musteri cakismasi, idempotent replay ve revoke/booking yarısını
kapsar. Frontend testleri REQUEST ve APPOINTMENT sonuc ekranlarini ayri dogrular.

Cikis kapisi: tum backend/frontend testleri, production build, OpenAPI parse,
`git diff --check` ve canli masaustu/mobil kontrolu basarili olmalidir.

Son dogrulama: backend 124/124 test, frontend 93/93 test, production build ve
OpenAPI YAML parse basarili. Bekleyen talebin slotu tutmamasi ve sonradan yapilan
onayda musaitligin yeniden kontrol edilmesi ayri entegrasyon testiyle korunur.
