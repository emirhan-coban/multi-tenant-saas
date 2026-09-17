# Plan V2 Faz 6 - Rezervasyon operasyonlari

Bu faz, kesinlesen randevunun musteri ve isletme tarafinda guvenli bicimde
yonetilmesini tamamlar. Temel garanti sudur: tasima basarisizsa eski randevu
kaybolmaz. Talep, iptal ve gerceklesen ziyaret metrikleri de ayri tutulur.

## Karar tablosu

| Islem | TRUSTED | NEW / REVIEW_REQUIRED |
| --- | --- | --- |
| Iptal | Tenant iptal sinirina kadar aninda, surum kontrollu | Ayni |
| Tasima | Yeni slot ayni transaction icinde kontrol edilip randevu atomik tasinir | Eski randevu korunur, onay talebi olusur |
| Tasima hedefi dolarsa | Islem geri alinir, eski saat korunur | Talep `NEEDS_RESELECTION` olur, eski saat korunur |
| Takvim kapatma | Kesin randevu varsa reddedilir | Kesin randevu varsa reddedilir |

Varsayilan iptal/tasima siniri 120 dakikadir. Isletme bunu rezervasyon ayarlari
ekranindan degistirebilir. Bekleyen tasima talebi yeni saati bloke etmez;
onay aninda slot tekrar kontrol edilir.

## Backend

- `V14__booking_operations.sql`, tenant bazli rezervasyon ayarlari, tasima
  talepleri, takvim kapanislari ve uygulama ici bildirimleri ekler. Yeni tablolar
  RLS ve `FORCE ROW LEVEL SECURITY` altindadir.
- Musteri iptal ve tasima komutlari booking ve appointment surumlerini birlikte
  ister. Eski ekranla yapilan islem `STALE_VERSION` ile durur.
- TRUSTED tasimasi ayni appointment kaydini atomik gunceller. Diger guven
  durumlari `PENDING_APPROVAL` talebi olusturur; mevcut appointment degismez.
- Yonetici onayinda slot veya appointment surumu degismisse talep
  `NEEDS_RESELECTION` olur. Eski appointment yine degismez.
- `BREAK` ve `CLOSED_DAY` kapanislari personel veya tum isletme kapsaminda
  olusturulur. Preview kesin randevulari listeler; cakisma varken create 409
  `CLOSURE_CONFLICT` doner.
- Booking horizon, minimum on sure, talep suresi, iptal siniri ve musteri
  limitleri tenant ayarlarindan okunur.
- Musteri ve personel bildirimleri ayri sahiplik denetimleriyle listelenir ve
  okundu olarak isaretlenir.
- Rapor ozeti talep, otomatik onay, manuel onay, red, sure dolumu, iptal,
  tamamlanan ve no-show sayilarini birbirine karistirmadan dondurur.

## Frontend

- Musteri hesabinda surum kontrollu iptal, tasima, `.ics` indirme, durum takibi
  ve islem bildirimleri bulunur. Onay bekleyen tasimada eski saatin korundugu
  acikca gosterilir.
- Onay ekraninda ilk ziyaret talepleri ile tasima talepleri ayri bolumlerdir.
- Randevu takviminde mola/kapali zaman formu, cakisma onizlemesi, aktif
  kapanislari listeleme ve gelecekteki kapanisi kaldirma bulunur.
- Rezervasyon ayarlari ekrani tum tenant politikalarini surum kontrollu kaydeder.
- Bildirim ekraninda uygulama ici gelen kutusu ve okundu islemi bulunur.
- Rapor ekraninda talepler, iptaller ve gercek ziyaretler ayri kartlarda sunulur.
- Rezervasyon baglantisi ekranindaki kopyalama, QR ve istege bagli WhatsApp
  paylasimi Faz 5'ten korunur.

## API ozeti

- `GET/PUT /api/booking-settings`
- `GET/POST /api/calendar-closures`, `POST /api/calendar-closures/preview`
- `GET /api/reschedule-requests`, `POST /api/reschedule-requests/{id}/approve|reject`
- `GET/POST /api/customer/v1/booking-requests/{id}/reschedule*`
- `GET /api/customer/v1/booking-requests/{id}/calendar`
- `GET/POST /api/notifications/in-app*`
- `GET/POST /api/customer/v1/notifications*`

Makinece okunabilir sozlesme `docs/contracts/booking-v1.openapi.yaml` icinde
`1.3.0-booking-operations` surumundedir.

## Dogrulama

Backend entegrasyon testleri atomik TRUSTED tasima, idempotent tekrar, eski
surum, onay aninda dolan hedef slot, eski appointment'in korunmasi, bildirim,
kapanis preview/cakisma ve ayar optimistic lock senaryolarini kapsar. Frontend
testleri surumlu iptal, tasima durumu, kapanis onizlemesi, yeni navigasyon ve
ayrilmis operasyon ekranlarini kapsar.
