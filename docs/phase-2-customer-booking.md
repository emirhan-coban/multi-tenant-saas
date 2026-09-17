# Faz 2 - Musteri rezervasyon akisi

Tarih: 16 Eylul 2026. Faz 2, Faz 1'de kurulan musteri kimligini gercek dukkan
kesfi, musaitlik ve rezervasyon talebiyle birlestirir. `NEW` ve
`REVIEW_REQUIRED` musteri icin onay talebi; `TRUSTED` musteri icin
dogrudan randevu uretilir.

> Guncel kural (Plan V2 Faz 5): Bekleyen talep bir takvim rezervasyonu degildir ve
> slotu bloke etmez. Asagidaki Faz 2 hold anlatiminin yerini
> [Faz V2-5 kurallari](phase-v2-5-auto-confirmation.md) almistir.

## 1. Teslim edilen akis

1. Musteri `/book/{tenantSlug}` adresinde dukkan, hizmet ve aktif personeli gorur.
2. WebGL sahnede bir berber koltuguna veya erisilebilir personel listesine tiklar.
3. Hizmet ve yerel tarih secildiginde backend calisma saati, izin ve kesin
   randevulari birlestirerek UTC slotlari dondurur.
4. Oturum yoksa secim URL query'sinde korunur; musteri giris/kayit sonrasinda ayni
   ekrana doner.
5. UUID `Idempotency-Key` ile talep olusturulur. Guven karari transaction icinde
   uygulanir.
6. `NEW/REVIEW_REQUIRED` sonucu `PENDING_APPROVAL`, `TRUSTED` sonucu
   `AUTO_CONFIRMED` olur. `BLOCKED` icin kayit uretilmez.

## 2. Backend ve veri modeli

Migration: `V9__customer_booking_flow.sql`.

- `staff.chair_key`, 3D sahne ile personel arasinda tenant bazli benzersiz ve
  kalici eslesmedir.
- `booking_requests.busy_until`, hizmet suresi ve buffer snapshot'inin bitisidir.
- Kesin randevu olusturma yollari ayni `pg_advisory_xact_lock` anahtarini alir.
  Kontrol ve insert ayni transaction'dadir; appointment exclusion constraint son
  savunma katmanidir.
- Public slug ve musteri talep sahipligi cozumlemesi dar kapsamli `SECURITY DEFINER`
  fonksiyonlariyla yapilir; tenant cozuldukten sonra `app.current_tenant` transaction
  icinde ayarlanir ve normal RLS devreye girer.
- Suresi gecen talep atomik olarak `EXPIRED` olur. Beklerken slotu bloke etmez.

## 3. Kurallar

| Kural | Uygulama |
| --- | --- |
| Rezervasyon ufku | Bugun dahil en fazla 30 gun |
| Minimum on sure | 60 dakika |
| Talep degerlendirme suresi | 15 dakika |
| Aktif bekleyen talep | Tenant basina musteri icin 1 |
| IP limiti | Redis/Bucket4j ile 5 talep/saat |
| Idempotency | Tenant + musteri + UUID anahtar; farkli govde 409 |
| Kota | Auto-confirm randevusu mevcut FREE/PRO aylik kotasina dahildir |
| Fiyat | Randevuya hizmet fiyati, sure ve buffer snapshot yazilir |

IP adresi Redis anahtarinda duz metin tutulmaz; adresten uretilen deterministik UUID
kullanilir. 429 yaniti `Retry-After` basligini tasir.

## 4. API

| Yontem | Endpoint | Auth |
| --- | --- | --- |
| GET | `/api/public/v1/shops/{tenantSlug}` | Public |
| GET | `/api/public/v1/shops/{tenantSlug}/availability` | Public |
| POST | `/api/customer/v1/booking-requests` | Customer access token |
| GET | `/api/customer/v1/booking-requests/{requestId}` | Kaydin sahibi musteri |

Public cevapta tenant timezone bulunur; slot zamanlari ISO-8601 UTC'dir. Web gosterimi
`Intl.DateTimeFormat` ile tenant timezone'unda yapilir. Staff/customer token siniri
Faz 1'deki gibi korunur.

## 5. Frontend

- Three.js ile gercek canvas/WebGL sahnesi, isik, golge, zemin, aynalar ve tiklanabilir
  koltuklar.
- Canvas kullanamayan veya klavye kullanan kisi icin ayni personeller normal buton
  listesinde her zaman bulunur; rezervasyon 3D'ye bagimli degildir.
- Hizmet, tarih ve slot secimi; masaustu ve mobil icin sabit boyutlu responsive grid.
- Oturum gecisi secimleri kaybetmez.
- Sonuc ekrani otomatik onay ile isletme onayi bekleyen durumu ayri metinlerle gosterir.
- Three.js rezervasyon route'uyla birlikte lazy yuklenir; personel panelinin ilk
  bundle'ina eklenmez.

## 6. Test kaniti

Backend entegrasyon kapsami:

- Bos PostgreSQL 16 uzerine V1-V9 migration.
- Public shop ve `chairKey`.
- Calisma saati tabanli musaitlik.
- NEW talebi, slotu bloke etmeme ve lazy expiry.
- Idempotent replay ve farkli govde conflict'i.
- TRUSTED auto-confirm, appointment FK ile fiyat/iletisim/sure snapshot'i.
- Iki musteri ayni slotu isterken tek kazanan.
- Redis tabanli IP limiter ve `Retry-After` donusumu.
- Faz 1 RLS/cross-tenant fixture regresyonu.

Frontend testi public booking route, sahne alternatifi, slot secimi, customer API
payload'i ve `PENDING_APPROVAL` sonucunu kapsar. Tum eski frontend testleri ve
production build de cikis kapisidir.

Son dogrulama: backend 106/106 test, frontend 80/80 test, production build,
OpenAPI YAML parse ve desktop/mobile Playwright gorunum kontrolu basarili.

## 7. Faz 2 siniri

Berber panelindeki onay/red kuyruğu ve karar verildiginde talebin randevuya atomik
donusmesi bir sonraki fazin isidir. Faz 2'nin cikisi, musteri tarafinin gercek talebi
guvenli ve yarisa dayanikli bicimde olusturabilmesidir.
