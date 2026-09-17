# Faz 3 - Berber onayi ve musteri guveni

Tarih: 17 Eylul 2026. Bu faz, Faz 2'de uretilen `PENDING_APPROVAL`
taleplerini berber panelinde sonuclandirir. Ilk kez gelen musteri berber kararini
bekler; basarili ziyaretten sonra ayni tenant icinde `TRUSTED` olur ve sonraki
uygun rezervasyonu otomatik kesinlesir.

> Guncel kural (Plan V2 Faz 5): Bekleyen talep slotu tutmaz. Onay aninda kesin
> randevular yeniden kontrol edilir; saat alinmissa `SLOT_UNAVAILABLE` doner.

## 1. Uctan uca akis

1. `NEW` veya `REVIEW_REQUIRED` musteri bir slot secer. Sistem 15 dakikalik
   degerlendirme suresi olan bir talep olusturur; slotu ayirmaz.
2. OWNER/MANAGER `/app/booking-requests` ekraninda musteri, hizmet, berber,
   tarih, not, tamamlanan/no-show sayilari ve kalan sureyi birlikte gorur.
3. Onayda ayni transaction icinde talep satiri kilitlenir, slot advisory lock'i
   alinir, cakisma ve kota yeniden kontrol edilir, fiyat/sure/iletisim snapshot'li
   randevu olusturulur ve talep `APPROVED` olur.
4. Redde talep `REJECTED` olur ve gerekce saklanir.
5. Musterinin bekleme ekrani talebi periyodik sorgular; onay, red veya sure dolumu
   sayfa yenilemeden gorunur.
6. Randevu sonucu guven gecisini artik Faz V2-4 kurallari belirler. Dogrulanmamis
   `COMPLETED` yalnizca sayaci artirir; ziyaret koduyla kanitlanmis ilk tamamlama
   `TRUSTED` yapar. `NO_SHOW`, engelli olmayan musteriyi `REVIEW_REQUIRED` yapar.

## 2. Backend ve transaction guvenceleri

Migration: `V10__booking_approval_and_trust.sql`.

- Karar kaydinda `decision_key`, `decision_action` ve istege bagli
  `rejection_reason` bulunur. Veritabani constraint'leri sonuclanmis kaydin karar
  metadata'siz kalmasini engeller.
- Onay ve red `SELECT ... FOR UPDATE` ile ayni talep uzerinde siralanir. Eszamanli
  iki karardan yalnizca biri 200 alir; digeri `BOOKING_ALREADY_DECIDED`/409 alir.
- Ayni UUID `Idempotency-Key` ve ayni aksiyon tekrar oynatilirsa var olan sonuc
  doner. Farkli anahtar veya ters aksiyon ikinci karar uretemez.
- Talep beklerken musteri manuel engellenirse onay `CUSTOMER_BLOCKED`/403 ile
  reddedilir; panel onay dugmesini kapatir.
- Onay, musteri rezervasyonu ve manuel randevu olusturma ayni personel+tarih
  advisory lock anahtarini kullanir. PostgreSQL exclusion constraint son savunma
  katmanidir.
- Talep suresi temizligi `REQUIRES_NEW` transaction'inda calisir. Onay endpoint'i
  `BOOKING_REQUEST_EXPIRED`/409 ile donse bile `EXPIRED` durumu commit edilir ve
  talep kuyruktan duser.
- Tum sorgular staff token'indaki tenant ile sinirlidir. Endpointler yalnizca
  OWNER/MANAGER rollerine aciktir; STAFF 403 alir.

## 3. Guven durumu kurallari

| Olay | Onceki durum | Yeni durum |
| --- | --- | --- |
| Ilk rezervasyon talebi | `NEW` | Berber onayi gerekir |
| Dogrulanmamis randevu tamamlandi | `NEW` | `NEW`, yalnizca sayac artar |
| Dogrulanmis randevu tamamlandi | `NEW` | `TRUSTED` |
| Dogrulanmis randevu tamamlandi | `REVIEW_REQUIRED` | `REVIEW_REQUIRED`, manuel inceleme korunur |
| Musteri gelmedi | `NEW` veya `TRUSTED` | `REVIEW_REQUIRED` |
| Manuel engelleme | Herhangi | `BLOCKED`, gerekce zorunlu |
| Tamamlama/no-show | `BLOCKED` | `BLOCKED` korunur |
| Manuel inceleme/guven | `TRUSTED`, `REVIEW_REQUIRED`, `BLOCKED` | Secilen durum |

`completed_count` ve `no_show_count` sadece randevunun CONFIRMED durumundan ilgili
son duruma gectigi transaction'da artar. Sonuclanmis randevu yeniden patch
edilemedigi icin sayac iki kez artmaz.

## 4. API

| Yontem | Endpoint | Yetki |
| --- | --- | --- |
| GET | `/api/booking-requests?status=PENDING_APPROVAL` | OWNER/MANAGER |
| POST | `/api/booking-requests/{id}/approve` | OWNER/MANAGER + Idempotency-Key |
| POST | `/api/booking-requests/{id}/reject` | OWNER/MANAGER + Idempotency-Key |
| PATCH | `/api/booking-customers/{customerId}/trust` | OWNER/MANAGER |
| GET | `/api/customer/v1/booking-requests/{id}` | Kaydin sahibi musteri |

Liste cevabi karar icin gereken musteri gecmisi ile hizmet/personel adlarini
icerir. Tum manuel guven degisikliklerinde `reason` ve `expectedVersion`
zorunludur; musteri manuel olarak `NEW` yapilamaz. Ziyaret dogrulamasinin guncel
kurallari [Faz V2-4 teknik notlarinda](phase-v2-4-visit-verification.md) bulunur.

## 5. Yan etkiler ve izlenebilirlik

- Onay, mevcut appointment created audit/outbox akisini ve musteri teyit e-postasi
  outbox kaydini ayni transaction'da uretir.
- `BOOKING_REQUEST_APPROVED`, `BOOKING_REQUEST_REJECTED`,
  `CUSTOMER_TRUST_CHANGED` ve `CUSTOMER_TRUST_AUTOMATED` audit olaylari yazilir.
- Transaction geri alinirsa randevu, karar, audit ve outbox birlikte geri alinir.

## 6. Frontend

- Sol menude OWNER/MANAGER icin `Onay Bekleyenler` route'u bulunur.
- Liste 30 saniyede bir yenilenir; manuel yenileme, yuklenme, bos liste ve hata
  durumlari vardir.
- Her satirda degerlendirme sayaci, tek tikla onay, gerekceli red ve musteri guven editoru
  bulunur. Engelleme nedeni girilmeden kaydetme acilmaz.
- Karar sonrasinda satir cache'den kaldirilir ve liste arka planda dogrulanir.
- Musteri sonucu beklerken 5 saniyede bir kendi talebini yeniler; `APPROVED`,
  `REJECTED` ve `EXPIRED` sonuclari ayri eylem/metinlerle gosterilir.
- Ekran 900px altinda aksiyonlari alta, 600px altinda detaylari tek sutuna alir.

## 7. Test kapsami ve cikis kapisi

Backend entegrasyon testleri tenant izolasyonu/RBAC, zengin kuyruk, atomik onay,
red, karar replay'i, approve-reject yarisi, kalici expiry, audit/outbox ve otomatik
ve manuel guven gecislerini kapsar. Frontend testleri yonetim route/RBAC, karar
ekrani, cache guncellemesi ve musteri sonuc polling'ini kapsar.

Son dogrulama (Faz V2-4 dahil): backend 119/119 test, frontend 92/92 test, production build,
OpenAPI YAML parse, `git diff --check` ve masaustu gorunum kontrolu basarili.
