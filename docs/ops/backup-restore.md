# Yedekleme ve geri yükleme

## Politika (pilot için önerilen)

| Konu | Karar |
|---|---|
| Yöntem | `pg_dump --format=custom` (mantıksal, sürümden bağımsız geri yükleme) |
| Sıklık | Günde 1 tam yedek (03:00 İstanbul, saklama işinden önce) |
| Hedef | RPO ≤ 24 saat, RTO ≤ 30 dakika (pilot ölçeği) |
| Saklama | Yerelde 14 gün; şifreli uzak kopya 30 gün (ör. `age`/`gpg` ile şifreleyip nesne depolamaya) |
| Erişim | Yedek kişisel veri içerir: dosya izni 600, uzak depoda ayrı kimlik bilgisi |
| Tatbikat | Ayda bir ve her büyük migration öncesi `restore-drill.sh` |
| Canlı DB | Tatbikat asla canlı veritabanına yazmaz; geçici `restore_drill_*` açar ve sonunda siler |

Üretimde yönetilen PostgreSQL kullanılırsa sağlayıcının PITR (WAL) özelliği açılmalı; bu betikler
ek, sağlayıcıdan bağımsız mantıksal kopya içindir.

## Komutlar

```bash
DATABASE_URL=postgresql://kullanici:parola@host:5432/saasdb ops/backup/backup.sh /guvenli/yedekler
ADMIN_URL=postgresql://admin:parola@host:5432/postgres \
SOURCE_URL=postgresql://kullanici:parola@host:5432/saasdb \
  ops/backup/restore-drill.sh /guvenli/yedekler/saasdb-YYYYMMDDTHHMMSSZ.dump
```

`restore-drill.sh` şunları doğrular: SHA-256, Flyway sürümü, tüm tabloların satır sayısı,
RLS açık+zorunlu tablolar, politika sayısı, security definer fonksiyonlar, tetikleyiciler ve
**süper kullanıcı olmayan geçici bir rolle** tenant izolasyonu (bilinmeyen tenant 0 satır,
gerçek tenant yalnızca kendi satırları). Süper kullanıcı RLS'i atladığı için bu ayrım zorunludur.

`backup.sh`, `pg_export_snapshot()` ile açık tuttuğu snapshot üzerinden dump ve
`*.meta.json` manifestini üretir. Restore tatbikatı tablo satır sayılarını bu manifestle
karşılaştırır; canlı veritabanında backup sonrasında oluşan değişiklikler sonucu etkilemez.

## Gerçek geri dönüş (olay anında)

İmaj kaynaklı bir geri dönüş için `ops/deploy/rollback.sh <önceki-image-tag>` kullanılır.
Bu yalnızca uygulama imajlarını değiştirir; şema ve veriyi geri almaz. Veri bozulması veya
kayıp şüphesinde aşağıdaki veritabanı kurtarma prosedürü ayrıca uygulanır.

Silme kaydı canlı veritabanından bağımsız, erişimi sınırlı TSV ledger olarak saklanmalıdır.
Geri yükleme sonrasında `ops/backup/reapply-customer-deletions.sh ledger.tsv` çalıştırılır;
betik `TARGET_URL` ile açılan veritabanında kişisel alanları yeniden temizler.

1. Yazmayı durdur: backend'i bakım moduna al veya ölçekle 0'a indir.
2. En son doğrulanmış yedeği seç (`.sha256` eşleşmeli).
3. Yeni veritabanına geri yükle (`pg_restore --no-owner --no-privileges --exit-on-error`), tatbikat doğrulamalarını çalıştır.
4. Uygulamayı yeni veritabanına yönlendir; Flyway `validate` ile başlasın (V17 dahil).
5. Yedek anı ile olay anı arasındaki randevular için `audit_log` ve e-posta/push kayıtlarından işletmeyi bilgilendir.

## Tatbikat kaydı — 17 Eylül 2026

Ortam: PostgreSQL 16.13, V1–V17 migration'ları sırayla uygulanmış veritabanı; tohum veri
3 işletme, 9 berber, 120 müşteri, 540 randevu, 540 rezervasyon talebi, 540 denetim kaydı,
540 bildirim, 50 push aboneliği, işletme bazlı feature flag geçersiz kılmaları.

```text
Yedek: saasdb-20260917T163420Z.dump (236K)
1) Bütünlük: sha256
   tamam
2) Geçici veritabanına geri yükleme: restore_drill_20260917163421
3) Doğrulama
   PASS flyway sürümü (17)
   PASS tablo satır sayıları (30 tablo birebir)
   PASS RLS açık/zorunlu tablolar (20 tablo, hepsi relrowsecurity+relforcerowsecurity)
   PASS RLS politika sayısı (20)
   PASS security definer fonksiyonlar (ops_queue_depth dahil 9)
   PASS tetikleyiciler (5)
   PASS RLS: bilinmeyen tenant 0 satır görür (0)
   PASS RLS: gerçek tenant yalnızca kendi satırlarını görür (t)
4) Süre (RTO ölçümü): 0s, yedek boyutu: 236K
SONUÇ: TATBİKAT BAŞARILI
```

Negatif deneme: yedek dosyasına tek bayt eklendi → `SHA-256 UYUŞMUYOR`, geri yükleme başlamadı.
Tatbikat sonunda geçici veritabanı ve geçici rol kalmadığı kontrol edildi.

**Sınır:** Ölçülen süre küçük tohum veri içindir. Pilot verisiyle ilk tatbikat süresi bu tabloya eklenmeli;
RTO hedefi gerçek boyutta ölçülmeden onaylanmış sayılmaz.
