# Faz 4 - Musteri hesabi ve self servis iptal

> Faz V2-6 notu: Sabit 60 dakikalik sinir artik tenant ayarindan okunur ve
> varsayilan deger 120 dakikadir. Iptal komutu booking ve appointment surumlerini
> birlikte ister. Guncel davranis `phase-v2-6-booking-operations.md` icindedir.

Tarih: 17 Eylul 2026. Bu faz, musteri rezervasyon yol haritasindaki Faz 4'tur.
Projenin daha once tamamlanan operasyon/raporlama Faz 4'u ile karismamasi icin
dosya adi `customer-self-service` olarak ayrilmistir.

## 1. Kullanici akisi

1. Musteri `/customer/account` ekraninda tum isletmelerdeki son 100 rezervasyonunu
   tek listede gorur.
2. Aktif ve gecmis sekmeleri; yaklasan, onay bekleyen, tamamlanan, iptal ve
   no-show sayaclarini birlikte sunar.
3. `PENDING_APPROVAL` talep hold suresi bitmeden iptal edilebilir. Slot hemen
   yeniden musait olur.
4. `AUTO_CONFIRMED` veya `APPROVED` randevu baslangictan en az 60 dakika once
   iptal edilebilir. Son 60 dakikada musteri isletmeyle iletisime gecmelidir.
5. Rezervasyon sonuc ekraninda hold icin saniyelik geri sayim, kesinlesen
   randevuda iptal son zamani ve uygun oldugu surece iptal komutu gosterilir.

## 2. Backend ve veri modeli

Migration: `V11__customer_booking_self_service.sql`.

- `cancellation_key`, `cancelled_at` ve `cancellation_source` alanlari iptal
  komutunun tekrar oynatilabilir ve izlenebilir olmasini saglar.
- Ayni `Idempotency-Key` replay'i ayni sonucu verir. Farkli anahtarla ikinci
  iptal `BOOKING_ALREADY_CANCELLED`/409 doner.
- Musteri sahipligi `resolve_customer_booking` security-definer fonksiyonuyla
  tenant baglami kurulmadan once dogrulanir. Baska hesabin kaydi 404 olarak
  gizlenir.
- Liste once hesaba ait tenant kimliklerini guvenli resolver ile bulur; her
  tenant sorgusu RLS transaction baglami kurulduktan sonra calisir.
- Iptal ve personel onayi ayni booking satirinda `PESSIMISTIC_WRITE` kilidi
  alir. Bekleyen talepte iki islem ayni anda baslasa bile yalnizca biri durum
  gecisi yapabilir.
- Kesinlesmis randevuda appointment satiri da kilitlenir. Appointment ve booking
  request ayni transaction'da `CANCELLED` olur; hata halinde ikisi de geri alinir.
- Musteri audit aktoru `customer_actor_id` alaninda tutulur. Personel aktoru gibi
  gosterilmez; immutable audit korumasi devam eder.

## 3. API

| Yontem | Endpoint | Sonuc |
| --- | --- | --- |
| GET | `/api/customer/v1/booking-requests` | Son 100 kayit ve durum ozetleri |
| GET | `/api/customer/v1/booking-requests/{id}` | Canli talep, `canCancel`, `cancellableUntil` |
| POST | `/api/customer/v1/booking-requests/{id}/cancel` | Idempotent self servis iptal |

Iptal endpoint'i zorunlu UUID `Idempotency-Key` basligi alir. `cancellableUntil`
bekleyen talepte hold sonu, kesin randevuda baslangictan 60 dakika oncesidir.

## 4. Frontend

- Hesap ekrani mobilde tek sutuna inen satir duzeni ve sabit boyutlu durum
  rozetleri kullanir.
- Iptal oncesi geri alinamazlik metni bulunan onay penceresi acilir.
- Yukleme, bos liste, hata/tekrar dene, aktif ve gecmis durumlari bulunur.
- API iptalinden sonra liste yeniden okunur; ozetler sunucu gercegiyle eslenir.
- Sonuc ekrani bekleyen talebi bes saniyede bir sorgulamaya devam eder ve hold
  sayacini her saniye gunceller.

## 5. Test ve cikis kapisi

Backend testleri migration, sahiplik izolasyonu, bekleyen ve kesin randevu
iptali, slotun serbest kalmasi, replay, farkli anahtar catismasi, audit aktoru ve
60 dakika sinirini kapsar. Frontend testleri hesap ozeti, aktif/gecmis sekmeleri,
onay penceresi, sonuc ekranindan iptal ve sozlesme sabitlerini kapsar.

Son cikis kapisi: tum backend/frontend testleri, production build, OpenAPI YAML
parse ve `git diff --check` birlikte basarili olmadan faz tamamlanmis sayilmaz.
