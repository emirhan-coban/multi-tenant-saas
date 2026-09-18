# Uçtan uca güven yaşam döngüsü

**Test:** `backend/src/test/java/com/saas/backend/booking/TrustLifecycleEndToEndTests.java`

Tek test metodu gerçek HTTP uçlarından (JWT filtreleri, rol kontrolleri, RLS, idempotency,
rate limit) geçer. Push servisleri ağa çıkmadan kaydedilir; kaydedilen gövde, tarayıcının
yapacağı gibi abonelik anahtarıyla **çözülerek** içerik doğrulanır. Doğrudan SQL yalnızca
iki yerde kullanılır: kurulum verisi ve "zaman atlaması" (randevuyu yarım saat önce bitmiş
gibi geçmişe taşımak; ziyaret kodu penceresi ve tamamlanma kuralı gerçek zamana bağlıdır).

## Adımlar ve beklenen durumlar

| # | Aktör / uç nokta | Beklenen | Neden önemli |
|---|---|---|---|
| 0 | Yönetici ve müşteri push aboneliği kaydeder | 204 | Bildirim zinciri gerçek abonelikle test edilir |
| 1 | Müşteri `POST /api/customer/v1/booking-requests` | `PENDING_APPROVAL`, `REQUIRE_APPROVAL`, trust `NEW`; onay kuyruğunda görünür; yöneticiye `BOOKING_REQUEST_CREATED` bildirimi ve şifreli push (VAPID, aes128gcm); push içinde müşteri adı yok | NEW müşteri asla kendiliğinden kesinleşmez |
| 2 | Yönetici `POST /api/booking-requests/{id}/approve` | `APPROVED`, randevu `CONFIRMED`; müşteri kendi talebinde `APPROVED` görür; müşteriye `BOOKING_REQUEST_APPROVED` + push (`/customer/account`) | Onay tek yetkili geçiştir |
| 3 | Zaman atlaması; müşteri `POST .../visit-code`; berber önce yanlış sonra doğru kodu girer | Yanlış: 400 `INVALID_VISIT_CODE` (sayılır, metrik artar); doğru: `verified=true`; trust hâlâ `NEW` | Doğrulama tek başına güven kazandırmaz |
| 4 | Berber `PATCH /api/appointments/{id}` `COMPLETED` | `TRUSTED`, `trust_reason=VERIFIED_COMPLETED_VISIT`, kanıt randevu kimliği, `completed_count=1`, `CUSTOMER_TRUST_AUTOMATED` denetim kaydı | Güven yalnızca doğrulanmış + tamamlanmış ziyaretle gelir |
| 5 | Müşteri ikinci talep | `AUTO_CONFIRMED`, `AUTO_CONFIRM`, `resultType=APPOINTMENT`; aynı idempotency anahtarıyla tekrar → aynı talep; onay kuyruğunda yok; müşteriye `APPOINTMENT_CONFIRMED`, yöneticiye `APPOINTMENT_AUTO_CONFIRMED` | Otomatik onay çalışır ve çift randevu üretmez |
| 6 | Yönetici `PATCH /api/booking-customers/{id}/trust` → `REVIEW_REQUIRED` (gerekçe + beklenen sürüm) | 200; 5. adımdaki randevu `CONFIRMED` kalır | Güven iptali geriye dönük iptal yapmaz |
| 7 | Müşteri üçüncü talep | `PENDING_APPROVAL`, trust `REVIEW_REQUIRED`; müşterinin toplam 2 randevusu; yönetici reddeder | İptalden sonra otomatik onay anında durur |
| 8 | Güven geri verilir; OWNER `PUT /api/feature-flags/booking.trusted_auto_confirm` `false` | TRUSTED müşterinin yeni talebi yine `PENDING_APPROVAL`; `FEATURE_FLAG_CHANGED` denetim kaydı | Pilotta otomatik onay tek anahtarla kapatılabilir |
| 9 | Müşteri `BLOCKED` yapılır, yeni talep | 403 `CUSTOMER_BLOCKED` | Engelleme tüm akışı keser |
| ✓ | `GET /api/booking-customers/{id}/trust-events` | Tam zincir: `NEW→TRUSTED`, `TRUSTED→REVIEW_REQUIRED`, `REVIEW_REQUIRED→TRUSTED`, `TRUSTED→BLOCKED` (4 olay) | Her geçiş izlenebilir |

## Eşlik eden testler

- `Phase8IntegrationTests.threeDKillSwitchFallsBackToListWithoutLosingBookingAbility` — 3D kapalıyken aynı seçimle liste talebi.
- `Phase8IntegrationTests.pushSubscriptionsAreValidatedDeliveredEncryptedAndCleanedUpWhenGone` — SSRF, 410 temizliği, 503 yeniden deneme.
- `Phase8IntegrationTests.logoutEverywhereRevokesRefreshTokensAndPushButAllowsFreshLogin`
- `Phase8IntegrationTests.retentionPurgesOnlyExpiredOperationalDataAndRecordsTheRun`
- `Phase8IntegrationTests.operationalMetricsRequireTheOpsTokenAndExposePhase8Meters`
- Mevcut `CustomerBookingIntegrationTests.trustRevocationAndAutoBookingSerializeWithoutUnsafeConfirmation` — güven iptali ile otomatik talep aynı anda gelirse güvensiz onay oluşmaz.

## Çalıştırma

```bash
cd backend
./mvnw -Dtest='TrustLifecycleEndToEndTests,Phase8IntegrationTests,WebPushCryptoTests,PushEndpointPolicyTests' test
./mvnw test   # tüm paket; mevcut testler yeni kurucu bağımlılıklarını da doğrular
```

> Bu testler bu oturumda yazıldı fakat çalıştırılamadı (Maven bağımlılıkları ve Docker yoktu).
> Kriptografik çekirdek (RFC vektörü, şifre çözme, VAPID) ve tüm SQL cümleleri ayrıca JDK 21 ve
> PostgreSQL 16 ile doğrulandı. İlk yerel çalıştırmada çıkan hataları bu belgeye not edin.

## Pilotta manuel prova (ekranlardan)

1. Yeni müşteri hesabıyla `/book/<slug>` → koltuk + saat → "Randevuyu onayla" → sonuç: "İşletme onayı bekleniyor".
2. Yönetici telefonunda push gelir → `/app/booking-requests` → Onayla. Müşteri telefonuna "Randevun onaylandı".
3. Randevu saatinde müşteri hesabında **Ziyaret kodu** → berber `/app/appointments` içinde kodu doğrular → randevu bitince durumu tamamlandı olarak işaretler.
4. `/app/customers` → müşteri **Güvenilir** görünür. Müşteri yeni randevu alır → anında "Randevun kesinleşti".
5. `/app/customers` → güven durumu **İncelenmeli** + gerekçe → müşterinin sonraki talebi yeniden onaya düşer.
