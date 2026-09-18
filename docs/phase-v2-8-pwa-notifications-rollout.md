# Plan V2 Faz 8 - PWA, bildirim, performans ve kontrollü yayın

Faz 8, çalışan rezervasyon motorunun üstüne canlıya çıkış için gereken işletim
katmanını ekler. Bu belge neyin yapıldığını, nasıl yapılandırıldığını ve **neyin
hangi yöntemle doğrulandığını** özetler.

| Alt belge | İçerik |
|---|---|
| [phase-8/e2e-trust-lifecycle.md](phase-8/e2e-trust-lifecycle.md) | NEW → onay → ziyaret → TRUSTED → otomatik onay → güven iptali senaryosu |
| [phase-8/accessibility-performance-qa.md](phase-8/accessibility-performance-qa.md) | Klavye, ekran okuyucu, %200 zoom, düşük donanım, 3D kill switch denetimi |
| [phase-8/pilot-runbook.md](phase-8/pilot-runbook.md) | Gerçek berber dükkânında pilot planı, kademeli açılış, geri dönüş |
| [ops/backup-restore.md](ops/backup-restore.md) | Yedekleme, geri yükleme tatbikatı ve gerçek tatbikat çıktısı |
| [ops/metrics-and-alerts.md](ops/metrics-and-alerts.md) | Metrik isimleri, eşikler, alarm önerileri |

## 1. Web push

- **Kripto harici kütüphanesiz:** `push/WebPushCrypto.java` RFC 8291 (aes128gcm)
  ve RFC 8292 (VAPID/ES256) uygular. RFC 8291 Ek A test vektörünü bayt bayt üretir
  (`WebPushCryptoTests`). Eğri dışı public key'ler reddedilir (geçersiz eğri saldırısı).
- **Abonelik:** `POST /api/customer/v1/push-subscriptions`, `POST /api/push-subscriptions`
  (+ `/unsubscribe`, `/test`). Uç nokta yalnızca HTTPS, 443 ve bilinen push
  servisleri (FCM, Mozilla, WNS, Apple) olabilir: sunucu bu adrese istek attığı için
  bu bir SSRF korumasıdır (`PushEndpointPolicy`). Hesap başına en fazla 10 aktif cihaz.
- **Kuyruk:** bildirimler `in_app_notifications` ile aynı transaction'da
  `push_deliveries` tablosuna yazılır; transaction geri alınırsa push da gitmez.
  `PushDispatcher` (ShedLock, 15 sn) partiler halinde `for update skip locked` ile gönderir.
- **Geçersiz abonelik temizliği:** 404/410 → abonelik kapatılır, bekleyen teslimatları
  düşürülür. 429/5xx/ağ hatası → üstel geri çekilme ile 5 deneme. 400/401/403/413 →
  kalıcı hata; art arda 10 hata aboneliği kapatır. Kapalı abonelikler 30 gün sonra silinir.
- **Kilit ekranı gizliliği:** bildirim metninde müşteri adı, telefon, e-posta, not yok.
- **Yeni bildirim olayları:** `BOOKING_REQUEST_CREATED` (yöneticiye),
  `BOOKING_REQUEST_APPROVED/REJECTED`, `APPOINTMENT_CONFIRMED` (müşteriye),
  `APPOINTMENT_AUTO_CONFIRMED` (yöneticiye). Mevcut taşıma/iptal bildirimleri de push alır.
- **İzin yönetimi (istemci):** `NotificationPermissionCard` izni yalnızca
  "Bildirimleri aç" tıklamasıyla ister; desteklenmeyen tarayıcı, iOS'ta ana ekrana
  ekleme gereksinimi, engellenmiş izin ve sunucu/bayrak kapalı durumlarını ayrı anlatır.
  Sunucu kaydı başarısızsa tarayıcı aboneliği geri alınır. Çıkışta bu cihazın aboneliği
  kaldırılır (paylaşılan cihaz).

VAPID anahtarı (üretimde zorunlu, `APP_PUSH_ALLOW_EPHEMERAL_KEYS=false`):

```bash
java backend/src/main/java/com/saas/backend/push/WebPushCrypto.java
# APP_PUSH_VAPID_PUBLIC_KEY=...  APP_PUSH_VAPID_PRIVATE_KEY=...  APP_PUSH_SUBJECT=mailto:ops@alanadiniz
```

Anahtar değişirse tüm abonelikler geçersizleşir; istemci eski aboneliği algılayıp yeniler.

## 2. PWA ve service worker

- `public/manifest.webmanifest`, 192/512/maskable/apple ikonlar, `offline.html`.
- `public/sw.js` yalnızca **aynı origin GET** isteklerine bakar. Asla önbelleğe alınmaz:
  `/api/`, `/actuator/`, `Authorization` başlıklı istekler, `Cache-Control: no-store/private`,
  `Set-Cookie`, opak/cors yanıtlar. Önbellekte yalnızca SPA kabuğu, hash'li derleme
  dosyaları, ikonlar ve offline sayfası bulunur (`tests/service-worker.test.ts`).
- Çevrimdışı: gezinme önce ağ, sonra son kabuk, sonra `offline.html`. Randevu
  oluşturma/onay/iptal çevrimdışı **yapılamaz**; panelde bant görünür, onay düğmesi pasifleşir.
- Bildirim tıklaması yalnızca aynı origin yollarına gider (`//evil`, `javascript:` → `/`).
- Service worker yalnızca üretim derlemesinde kaydolur; yeni sürümde "Yenile" bandı çıkar.
- "Ana ekrana ekle" daveti: Chrome/Edge'de yerel istem, iOS'ta Safari talimatı; kapatılınca 30 gün gizli.

## 3. Feature flag / kontrollü yayın

`feature_flags`: global satır + işletme geçersiz kılması. **Global kapalıysa işletme açamaz**
(acil durum anahtarı). Değişiklikler anında etkilidir (önbellek yok) ve `audit_log`'a yazılır.

| Anahtar | Etki |
|---|---|
| `booking.3d` | Kapalı: `/scene` 409 `FEATURE_DISABLED`, `WEB_3D` talepleri 409 (`details.fallback=WEB_LIST`), istemci 3D kodu/modelini hiç indirmeden liste moduna geçer |
| `booking.trusted_auto_confirm` | Kapalı: TRUSTED müşteri de onaya düşer (yeni talep ve taşıma) |
| `push.notifications` | Kapalı: yeni push kuyruğa alınmaz, personel aboneliği reddedilir |
| `pwa.install` | Ana ekrana ekleme davetini gizler |

API: `GET /api/public/v1/shops/{slug}/features`, `GET/PUT /api/feature-flags/{key}` (yalnızca OWNER, gerekçe zorunlu).
Global anahtar SQL ile değiştirilir: `update feature_flags set enabled=false,reason='...' where flag_key='booking.3d' and tenant_id is null;`

## 4. Metrikler

Micrometer: `saas.api.errors{code,status}`, `saas.http.server_errors`, `http.server.requests`
(histogram + SLO 100ms/300ms/1s/3s), `saas.booking.decisions{decision,source}`,
`saas.trust.transitions{from,to,reason}`, `saas.queue.pending|oldest_age_seconds{queue}`,
`saas.abuse.events{kind}`, `saas.push.deliveries{outcome}`, `saas.feature.disabled_hits{flag}`,
`saas.retention.deleted_rows{dataset}`. `/actuator/metrics` yalnızca `APP_OPS_METRICS_TOKEN`
ile açılır; kullanıcı JWT'si erişim vermez. Ayrıntı: [ops/metrics-and-alerts.md](ops/metrics-and-alerts.md).

## 5. Oturum, idempotency ve veri saklama

- **Tüm cihazlardan çıkış:** `POST /api/auth/logout-all`, `POST /api/customer/v1/auth/logout-all`.
  O saniyeden önce üretilmiş refresh token'lar reddedilir, hesabın push abonelikleri kapanır.
  Access token'lar kısa ömürlüdür (60 dk) ve süresi dolunca yenilenemez. Arayüz: müşteri hesabı ve personel "Hesabım".
- **Saklama işi** (`DataRetentionService`, her gün 03:30 İstanbul, ShedLock, tenant başına ayrı transaction ve RLS bağlamı):

| Veri | Süre |
|---|---|
| `idempotency_keys` | 48 saat (en az 24 zorunlu) |
| Doğrulanmamış `visit_verifications` | süresi dolduktan 30 gün sonra |
| Okunmuş `in_app_notifications` / tümü | 90 gün / 180 gün |
| Tamamlanmış `outbox` | 30 gün |
| Tamamlanmış `push_deliveries` | 7 gün |
| Kapatılmış `push_subscriptions` | 30 gün |

Randevu, rezervasyon, güven olayları ve değiştirilemez `audit_log` silinmez (raporlama ve güven kanıtı).
Her tur `data_retention_runs` tablosuna yalnızca sayılarla kaydedilir.

## 6. Migration

`V17__pwa_push_flags_retention.sql`: push tabloları, feature flag, `sessions_valid_after`,
`data_retention_runs`, `ops_queue_depth()` (yalnızca sayı döndüren security definer).
Yerel PostgreSQL 16 üzerinde V1–V17 sırayla uygulandı ve servislerdeki SQL cümleleri tek tek çalıştırıldı.

## 7. Doğrulama durumu — dürüst tablo

| Kontrol | Yöntem | Sonuç |
|---|---|---|
| Web testleri (21 dosya) | `npm test` (Vitest) | **119/119 geçti** |
| TypeScript + üretim derlemesi | `tsc -b`, `vite build` | **Geçti** |
| Tarayıcı denetimi (PWA, cache, klavye, ARIA, kontrast, %200 zoom, düşük donanım, kill switch) | `web/qa/phase8_audit.py`, Chromium | **17/17 geçti** |
| RFC 8291 test vektörü, VAPID imza doğrulaması, uçtan uca şifre çözme, eğri dışı anahtar reddi | JDK 21 ile derlenip çalıştırıldı | **Geçti** |
| V1–V17 migration + Faz 8 SQL cümleleri | PostgreSQL 16.13 | **Geçti** |
| Backup/restore tatbikatı | `ops/backup/*.sh`, PostgreSQL 16.13 | **Geçti** (bkz. ops/backup-restore.md) |
| Backend derleme ve entegrasyon testleri (`TrustLifecycleEndToEndTests`, `Phase8IntegrationTests`, `WebPushCryptoTests`, `PushEndpointPolicyTests` ve mevcutlar) | `./mvnw test` | **Geçti** — Faz 9'da kullanıcının makinesindeki Docker ile çalıştırıldı; tüm test sınıfları geçti. Faz 9'dan sonra aynı testler kısıtlı veritabanı rolüyle (gerçek RLS altında) koşuyor. |
| Gerçek berber dükkânında pilot | İnsan + işletme | **Yapılmadı**, plan hazır: phase-8/pilot-runbook.md |
| Gerçek cihazda push (Android Chrome, iOS 16.4+ ana ekran, masaüstü) | Manuel | **Yapılmadı**, kontrol listesi pilot runbook'ta |

Canlıya almadan önce zorunlu komut:

```bash
cd backend && ./mvnw test
```
