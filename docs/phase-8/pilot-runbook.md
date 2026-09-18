# Gerçek berber dükkânında pilot

> Durum: **plan hazır, pilot henüz yapılmadı.** Bu belge sahada uygulanacak adımları, başarı
> ölçütlerini ve geri dönüş kurallarını tanımlar. Sonuçlar "Pilot günlüğü" bölümüne yazılır.

## Kapsam

- 1 dükkân, 2–3 berber, 2 hafta; en az 60 çevrim içi talep hedefi.
- Dükkân sahibinin açık onayı, KVKK aydınlatma metni ve müşteriye "pilot" bilgisi.
- Destek: pilot süresince mesai saatinde 15 dk içinde dönüş yapan bir sorumlu.

## Önkoşullar (T-7 gün)

- [ ] `cd backend && ./mvnw test` yeşil (Faz 8 testleri dahil) ve sonuç bu belgeye eklendi.
- [ ] Üretim: HTTPS alan adı; `APP_PUSH_VAPID_PUBLIC_KEY/PRIVATE_KEY`, `APP_PUSH_SUBJECT`,
      `APP_PUSH_ALLOW_EPHEMERAL_KEYS=false`, `APP_OPS_METRICS_TOKEN`, güçlü `app.jwt.secret`.
- [ ] Günlük yedek zamanlandı; pilot verisiyle `restore-drill.sh` başarılı ve süre kaydedildi.
- [ ] Alarmlar (ops/metrics-and-alerts.md) bir kanala bağlandı ve test alarmı alındı.
- [ ] İşletme: hizmetler, fiyatlar, çalışma saatleri, izinler, 3D sahne yayında, rezervasyon bağlantısı/QR basıldı.
- [ ] Cihaz testi — her birinde kurulum, izin, test bildirimi, bildirime tıklama, çıkışta bildirimin durması:
  - [ ] Android Chrome (berberin telefonu)
  - [ ] iPhone Safari 16.4+ ana ekrana eklenmiş uygulama
  - [ ] Masaüstü Chrome/Edge (kasa bilgisayarı)
  - [ ] Düşük seviye Android (≤3 GB RAM): 3D düşük kaliteye iner, liste görünümü akıcı
- [ ] Erişilebilirlik: VoiceOver (iOS) ve TalkBack (Android) ile liste görünümünden baştan sona bir rezervasyon.

## Kademeli açılış (bayraklarla)

| Aşama | Süre | `booking.3d` | `booking.trusted_auto_confirm` | `push.notifications` | Çıkış ölçütü |
|---|---|---|---|---|---|
| 0 İç prova | 1 gün | açık | kapalı | açık | Ekip 10 sahte rezervasyonu uçtan uca tamamladı |
| 1 Manuel onay | 1. hafta | açık | **kapalı** | açık | ≥ 25 gerçek talep, 5xx yok, onay yanıt süresi medyan < 15 dk |
| 2 Güven + otomatik onay | 2. hafta | açık | **açık** | açık | ≥ 5 müşteri ziyaret koduyla TRUSTED oldu, otomatik onayda çakışma yok |
| 3 Genişletme kararı | gün 14 | — | — | — | Aşağıdaki başarı ölçütleri |

## Başarı ölçütleri

| Ölçüt | Hedef | Kaynak |
|---|---|---|
| Talep → kesin randevu dönüşümü | ≥ %70 | `/app/reports` talep özeti |
| Onay kuyruğu yaşı (mesai içi) | p90 < 30 dk | `saas.queue.oldest_age_seconds` |
| Gelmeyen (no-show) oranı | önceki dönemden kötü değil | raporlar |
| Push teslim | `sent / (sent+failed)` ≥ %95 | `saas.push.deliveries` |
| API p95 gecikme | < 1 s | `http.server.requests` |
| 3D kullanımı | kayıt; ≥ %30 olursa 3D sürdürülür | `saas.booking.decisions{source}` |
| Destek talebi | haftada ≤ 3 "nasıl yapılır" | pilot günlüğü |
| Güvenlik olayı | 0 | denetim kaydı, alarmlar |

## Geri dönüş kuralları (karar yetkisi: pilot sorumlusu)

| Belirti | Hemen yapılacak |
|---|---|
| 3D donma/çökme şikâyeti, düşük cihazlarda yavaşlık | `booking.3d` işletme bazında kapat → herkes liste modunda, randevular etkilenmez |
| Otomatik onayda yanlış/çakışan randevu | `booking.trusted_auto_confirm` kapat; etkilenen müşterileri ara |
| Push spam / yanlış kişiye bildirim | `push.notifications` kapat; `push_subscriptions` kayıtlarını incele |
| Veri tutarsızlığı | Yazmayı durdur, ops/backup-restore.md "Gerçek geri dönüş" |
| Sürüm hatası | Önceki imaja dön; V17 geriye uyumludur (yeni tablolar/kolonlar eski kodu bozmaz) |

## Günlük kontrol listesi (pilot sorumlusu, 5 dk)

1. Alarm kanalında gece olayı var mı?
2. `data_retention_runs` son tur ve yedek dosyası (boyut, sha256) mevcut mu?
3. Onay bekleyen talep var mı, en eskisi kaç dakikalık?
4. Push `failed` ve `dropped_gone` sayıları olağan mı?
5. Berberden sözlü geri bildirim: "bugün sistem seni yavaşlattı mı?"

## Geri bildirim formu (hafta sonu, berber ve 5 müşteri)

- Randevu almak kaç dakika sürdü? (müşteri)
- 3D mi liste mi kullandın, neden?
- Bildirim zamanında geldi mi, gereksiz bildirim oldu mu?
- Ziyaret kodu adımı kafa karıştırdı mı?
- 1–5: Bir arkadaşına önerir misin?

## Pilot günlüğü

| Tarih | Aşama | Olay / ölçüm | Karar |
|---|---|---|---|
| | | | |
