# Faz 1 - Musteri kimligi ve rezervasyon veri temeli

Tarih: 16 Eylul 2026. Faz 1, Faz 0 sozlesmesindeki personelden bagimsiz musteri
kimligini ve tenant bazli guven kaydini calisan hale getirir. Rezervasyon talebi
tablosu hazirdir; slot secimi ve talep olusturma endpointi Faz 2 kapsamindadir.

## 1. Teslim edilenler

- Global `customer_accounts` hesabi: normalize e-posta, BCrypt sifre, ad soyad,
  opsiyonel telefon, aktiflik ve olusturma zamani.
- Tenant bazli `tenant_customers`: `NEW/TRUSTED/REVIEW_REQUIRED/BLOCKED`, sayaclar,
  engel gerekcesi ve optimistic version.
- `booking_requests`: musteri, personel, hizmet, saat, karar/durum, 15 dakikalik
  hold, idempotency anahtari, request hash ve karar veren personel alani.
- `appointments.customer_id` artik ayni tenant icindeki `tenant_customers` kaydina
  composite foreign key ile baglidir.
- Musteriye ozel access/refresh JWT, kayit, giris, refresh ve `/me` endpointleri.
- Ayri web oturumu, `/customer/login` ve korumali `/customer/account` ekranlari.

## 2. Veri modeli

Migration: `backend/src/main/resources/db/migration/V8__customer_booking_foundation.sql`.

`customer_accounts` tenant disi kimliktir. Ayni kisi tek hesapla farkli isletmelere
gidebilir. Guven global degildir; her isletme icin ayri `tenant_customers` kaydi
olusturulur. Boylece bir berberin guvendigi musteri diger isletmede otomatik olarak
guvenilir sayilmaz.

`booking_requests` su garantileri veritabaninda uygular:

- Musteri, personel, hizmet ve olusan randevu ayni tenant'a ait olmak zorundadir.
- Tenant + musteri + idempotency anahtari benzersizdir.
- `PENDING_APPROVAL` kaydinda `hold_expires_at` zorunludur; diger durumlarda yoktur.
- `AUTO_CONFIRMED` ve `APPROVED` bir `appointment_id` olmadan kaydedilemez.
- Karar zamani ve karar veren kullanici birlikte bulunur veya birlikte null olur.
- `tenant_customers` ve `booking_requests` RLS + FORCE RLS altindadir.

## 3. Musteri auth API

| Yontem | Endpoint | Auth | Sonuc |
| --- | --- | --- | --- |
| POST | `/api/customer/v1/auth/register` | Public | 201 + tokenlar + musteri |
| POST | `/api/customer/v1/auth/login` | Public | 200 + tokenlar + musteri |
| POST | `/api/customer/v1/auth/refresh` | Public refresh | 200 + donmus token cifti |
| GET | `/api/customer/v1/me` | `customer_access` | Aktif musteri profili |

Kayit govdesi: `email`, en az 8 karakter `password`, `fullName`, opsiyonel `phone`.
E-posta kucuk harfe cevrilir. Sifre hicbir yanitta donmez ve BCrypt olarak saklanir.
Access ve refresh tokenlarinda benzersiz `jti` bulunur. Refresh endpointi yeni bir
token cifti uretir; Faz 1 stateless JWT kullandigi icin onceki refresh tokeni kendi
suresi dolana kadar sunucu tarafinda iptal edilmez.

Hata kodlari:

- `CUSTOMER_EMAIL_EXISTS` -> 409
- `INVALID_CUSTOMER_CREDENTIALS` -> 401
- `INVALID_CUSTOMER_REFRESH_TOKEN` -> 401
- Eksik/gecersiz alan -> 400 `VALIDATION_ERROR`
- Yanlis token tipi veya eksik token -> 401 `UNAUTHORIZED`
- Gecerli personel tokeniyla musteri alani -> 403 `FORBIDDEN`

## 4. Guvenlik siniri

`CustomerJwtAuthFilter` sadece `/api/customer/v1/**` altinda calisir. Musteri tokeni
tenant/personel baglami olusturmaz ve `/api/me` dahil personel API'lerinde anonim
kalir. Personel access tokeni musteri alaninda `ROLE_CUSTOMER` olmadigi icin reddedilir.

Web tarafinda personel refresh tokeni `saas.refreshToken`, musteri refresh tokeni
`saas.customer.refreshToken` anahtarinda tutulur. Access tokenlar yalnizca bellektedir.
Musteri istemcisi eszamanli refresh isteklerini tek promise ile birlestirir ve 401
sonrasinda istegi en fazla bir kez tekrarlar.

## 5. RLS ve tenant izolasyonu

Entegrasyon testi BYPASSRLS olmayan PostgreSQL roluyle iki tenant olusturur. Rol,
transaction icinde sadece `app.current_tenant` ile secilen kayitlari gorur. Commit
sonrasinda ayar sifirlanir ve ayni baglanti veri goremez. Baska tenant'in musteri
profilini kullanarak booking request eklemek composite foreign key ile reddedilir.

Gelistirme compose'u halen superuser kullandigi icin Faz 0'daki production runtime
rolu cikis kapisi gecerlidir. Yeni tablolarda policy ve transaction davranisi buna
ragmen gercek kisitli rolle test edilmektedir.

## 6. Web akisi

- `/customer/login`: giris ve hesap olusturma ayni formda.
- `/customer/account`: ad, e-posta, telefon ve oturum kapatma.
- Korunan adrese oturumsuz gidis `/customer/login` adresine yonlenir.
- Mevcut musteri refresh tokeni acilista session restore yapar.
- Personel `/login` ve `/app` akisi degismemistir.

## 7. Faz 1 cikis kriterleri

- Tum Flyway migrationlari bos PostgreSQL 16 veritabanina uygulanir.
- Musteri kayit/giris/refresh/me ve iki yonlu token izolasyonu HTTP testinden gecer.
- Sifrenin hash saklandigi, e-postanin normalize edildigi dogrulanir.
- Yeni tenant tablolari RLS ve cross-tenant FK testinden gecer.
- Frontend musteri route, register ve restore testleri gecer.
- Tum eski backend/frontend regresyonlari ve production build gecer.

Son dogrulama: backend 102/102 test, frontend 79/79 test, production build ve
OpenAPI YAML parse kontrolu basarili.

Faz 2, public dukkan verisini, musaitlik sorgusunu ve `NEW` musteri icin gercek
`PENDING_APPROVAL` talep olusturmayi bu temel uzerine ekleyecektir.
