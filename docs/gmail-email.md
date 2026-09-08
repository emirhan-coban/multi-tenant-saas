# Gmail ile müşteri e-postaları

## Güvenli kurulum

Proje: /Users/emreayan/Desktop/multi-tenant-saas

.env.mail dosyası yerel ve Git tarafından yok sayılmıştır. İzinleri yalnızca dosya sahibine okuma/yazma sağlayacak şekilde ayarlanmıştır. Dosyayı, uygulama şifresini veya dolu ekran görüntüsünü sohbet/GitHub üzerinden paylaşmayın. Docker erişimi olan bir yönetici container ortam değişkenlerini görebilir; bu geliştirme kurulumu production secret manager değildir.

Dosyayı kendi bilgisayarınızda düzenleyin:

```dotenv
GMAIL_ADDRESS=kendi-adresiniz@gmail.com
GMAIL_APP_PASSWORD=16karakterliuygulamasifreniz
MAIL_ENABLED=true
MAIL_DELIVERY_MODE=test
```

Yukarıdaki adres ve parola örnek yer tutucudur. Google'ın verdiği uygulama şifresini boşluksuz girin; normal Gmail parolanızı kullanmayın.

Proje klasöründeyken:

```bash
docker compose --profile app up -d --force-recreate backend
```

.env.mail değişikliklerinde sadece restart yeterli değildir; container ortamının yenilenmesi gerekir. Ayarları görüntülemek için docker inspect veya tam docker compose config çıktısını paylaşmayın; sırları açığa çıkarabilir.

## İlk test

1. Uygulamada Bildirimler sayfasını açın. Gmail bilgileri tamamlandığında gönderim açık ve test modu görünür. Bu durum SMTP girişinin doğrulandığı anlamına gelmez; yalnızca yerel ayarların mevcut olduğunu gösterir.
2. Yeni randevu oluşturun; müşteri e-posta adresine **GMAIL_ADDRESS ile aynı adresi** yazın.
3. “Müşteriye randevu e-postaları gönder” seçeneğini işaretleyin.
4. En geç sonraki job kontrolünde (normalde yaklaşık bir dakika) onay e-postası işlenir. Gelen kutusu, Spam ve Gönderilenler klasörlerini kontrol edin.
5. Test modu başka müşteri adreslerine gönderim yapmaz; bu adreslere ait zamanı gelmiş olaylar SKIPPED olarak işaretlenir, daha sonra otomatik yeniden gönderilmez.

İlk test başarılı olduktan sonra gerçek müşteri gönderimi istenirse MAIL_DELIVERY_MODE=live yapılır ve backend yeniden oluşturulur. Live açılınca uygun bekleyen olaylar da işlenebilir. Gerçek gönderim için müşterinin adresinin doğru olduğundan ve randevu bildirim seçeneğinin istendiğinden emin olun.

## Davranış

- Müşteri e-postası isteğe bağlıdır. E-posta bildirimini seçmek adresi zorunlu yapar.
- Yeni randevu: CUSTOMER_CONFIRMATION, hemen kuyruğa.
- Randevuya 24 saatten fazla varsa: CUSTOMER_REMINDER, başlangıçtan 24 saat önce.
- 24 saatten yakın randevuda çift mesaj oluşturmamak için yalnızca onay gönderilir.
- Saat değişimi: bekleyen eski müşteri olayları atlanır; yeni saat bilgisi ve uygunsa yeni hatırlatma kuyruğa girer.
- İptal: bekleyen onay/hatırlatmalar atlanır; iptal mesajı kuyruğa girer.
- Tamamlanmış, iptal edilmiş veya artık zamanı geçmiş randevu için eski onay/hatırlatma gönderilmez.
- Gönderim kapalı veya bilgiler eksikse kayıt PENDING kalır; başarısız deneme sayısı artırılmaz.
- SMTP hata verirse mevcut artan bekleme mekanizmasıyla en fazla 5 deneme; sonra FAILED.
- SENT, SMTP sunucusunun mesajı kabul ettiğini ifade eder; gelen kutusuna teslim garantisi değildir.
- Scheduler tenant başına 10 uygun olay işler. Yoğunluk varsa teslim gecikebilir.
- E-posta gövdesi Türkçe, UTF-8 düz metindir. İşletme, hizmet, müşteri adı ve işletmenin saat dilimindeki randevu zamanı bulunur. Telefon ve özel notlar gönderilmez.
- Sistemdeki işletmeler ortak yapılandırılmış Gmail göndericisini kullanır. İşletme başına ayrı SMTP hesabı bu sürümde yoktur.
- “Oluşturduğum randevular için hatırlatma gönder” ayarı eski kullanıcı/konsol hatırlatması içindir; müşteri e-posta tercihi randevu üzerindeki ayrı seçimdir.
- Mevcut eski randevulara otomatik e-posta alıcısı veya toplu gönderim eklenmez.
- Müşteri adresi/bildirim seçimi bu sürümde oluşturma sırasında belirlenir; mevcut randevunun adresini değiştirme arayüzü yoktur.

## Teknik yapı

- V7 migration: appointments.customer_email, email_notifications; outbox.recipient_email.
- AppointmentDtos.Create alanları: customerEmail, emailNotifications. Eski istemciler bu alanları göndermeden çalışabilir.
- CustomerEmailService: transaction içinde olayları oluşturur; gönderimde tenant/randevu/zaman/durum/alıcıyı tekrar doğrular.
- GmailSender: Spring JavaMailSender üzerinden smtp.gmail.com:587, zorunlu STARTTLS ve sunucu kimliği doğrulaması.
- SMTP bağlantı/okuma/yazma timeout: 5 saniye.
- GET /api/notifications/email-status: OWNER/MANAGER için enabled/configured/ready/mode; parola ve adres döndürmez.
- Gizli bilgiler uygulama veya hata loguna yazılmaz.
- X-TenantOS-Event-ID başlığı korelasyon içindir. Gmail SMTP exactly-once/idempotency API sağlamaz; gönderim sonrası commit öncesi process kaybında mükerrer mesaj ihtimali vardır.
- Randevu güncellemesi ile gönderimin çok kısa bir zaman aralığında yarışması dış e-posta sisteminde geri alınamaz. Gerçek sağlayıcı entegrasyonlarında bu sınır dikkate alınmalıdır.

## Doğrulama

Gerçek Google hesabına bağlanmadan yerel SMTP test sunucusuyla gönderim ve Türkçe MIME içeriği doğrulandı. PostgreSQL/Redis Testcontainers testleri müşteri opt-in, alıcı, idempotency, kapalı gönderim, test modu, iptal, taşıma, hatırlatma ve hata tekrarlarını kapsar. Gerçek Gmail doğrulaması kullanıcı kendi yerel ayarlarını girdikten sonra yapılmalıdır.

## Resmî kaynaklar

- [Google: Uygulama Şifreleri](https://support.google.com/accounts/answer/185833?hl=tr)
- [Google: Gmail SMTP ayarları](https://support.google.com/mail/answer/7104828?hl=en-uk)
- [Spring Boot: E-posta gönderimi](https://docs.spring.io/spring-boot/reference/io/email.html)
