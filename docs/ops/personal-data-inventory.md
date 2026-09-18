# Kişisel veri envanteri

Bu belge teknik silme kapsamını tanımlar; hukuki saklama ve aydınlatma onayı yerine
geçmez. Sentetik veriyle doğrulanır ve migration değiştikçe güncellenir.

| Kaynak | Kişisel alan/kapsam | Hesap silmede teknik davranış |
|---|---|---|
| `customer_accounts` | e-posta, ad, telefon, parola özeti | Anonimleştirilir, pasifleştirilir, tombstone yazılır |
| `tenant_customers` | müşteri hesabı-işletme ilişkisi, güven sayaçları | Ticari geçmiş için ilişki korunur; hesap anonimdir |
| `appointments` | ad, telefon, e-posta, not | Anonimleştirilir; randevu durumu/tarih ticari kayıt olarak kalır |
| `booking_requests` | müşteri ilişkisi, not, idempotency/request kayıtları | Not temizlenir; karar ve concurrency kanıtı korunur |
| `outbox` | alıcı e-postası, müşteri payload'ı | Alıcı boşaltılır, payload `{}` yapılır; gönderim atlanır |
| `in_app_notifications` | müşteri alıcısı, başlık/gövde | Müşteri alıcılı kayıt silinir |
| `push_subscriptions`, `push_deliveries` | endpoint ve teslimat metadatası | Teslimatlar ve abonelikler silinir |
| `account_tokens`, `account_email_outbox` | reset/doğrulama token ilişkisi ve şifreli mesaj | Token ve bağlı mesaj kuyruğu silinir |
| `audit_log` | aktör, tenant, işlem kanıtı | Değiştirilemez; kişisel payload eklenmemesi zorunludur |
| `kvkk_requests` | subject UUID, işlem zamanı/sonucu | Silme/export kanıtı olarak korunur |
| yedekler | tüm veritabanı snapshot'ı | Şifreli saklama politikasına tabi; restore sonrası tombstone ledger replay edilir |

Silme transaction'ı önce hesabı pasifleştirir, sonra kuyruk alanlarını temizler ve
`account_deletion_tombstones` kaydı üretir. Worker'lar pasif/anonymized hesabın müşteri
mesajını göndermez. Yeni kişisel veri alanı ekleyen migration, bu tabloya ve silme
fonksiyonuna kapsam kararı eklemeden tamamlanmış sayılmaz.