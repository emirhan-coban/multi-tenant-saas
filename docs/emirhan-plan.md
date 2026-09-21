# Satışa hazırlık uygulama planı

Tarih: 18 Eylül 2026. Durum: uygulama başladı; ilk güvenlik paketi kısmen tamamlandı.

## Hedef ve kapsam

Bağımsız berberler için mevcut ürünü önce 1–3 işletmede güvenle pilotlanabilir,
ardından ilk 10 işletmeye ücretli sunulabilir hale getirmek. Spring Boot,
PostgreSQL, Redis ve React mimarisi korunacak. Her aşama küçük, incelenebilir
iş paketleriyle uygulanacak; ilgili kabul ölçütleri geçmeden aşama tamamlanmayacak.

Bu belge uygulama sırasının kaynağıdır. `launch-plan-and-budget.md` içindeki
ticari seçenekler arka plan olarak korunur; sağlayıcı, fiyat ve süre varsayımları
burada kesin karar kabul edilmez. SMS pilot ön koşulu değildir: gerekli kanal
pilot işletmelerle belirlenir. Güvenilir hesap e-postası ise pilot ön koşuludur.

## Doğrulanmış başlangıç

- Backend başlangıç ölçümü: Java 21 ve geçici Testcontainers ortamında 156 test;
  155 başarılı, 1 saat dilimi karşılaştırma hatası. Test `Instant` karşılaştırmasına
  geçirildi; Docker yeniden açıldığında tam paket iki saat diliminde tekrar çalıştırılacak.
- Web: mevcut yerel node_modules içinde Rolldown native bağımlılığı eksik.
  Kaynak ve lockfile geçici klasöre kopyalanıp temiz kurulduğunda 127 test geçti,
  production build başarılı. Bu durum uygulama hatası olarak sınıflandırılmayacak.
- Build: ana JS yaklaşık 560 kB, 3D JS yaklaşık 1.084 kB (gzip 171/296 kB).
  Gerçek telefon performansı henüz bu incelemede ölçülmedi.
- Canlı üretim, sağlayıcı teslimatı, kapasite ve yeni şemayla restore doğrulanmadı.
- Depoda önceden bulunan `launch-plan-and-budget.md` korunacak.

## Aşama 1 — Güvenlik ve erişim (pilot engeli)

### 1.1 Test tabanı
- [x] Saat dilimine bağlı testi `Instant` karşılaştırmasıyla düzelt; iş kuralı kontrollerini koru.
- [x] İlgili testi UTC ve Europe/Istanbul ortamlarında doğrula.
- [x] Node sürümü/temiz kurulum yolunu CI ile uyumlu belgele.

### 1.2 Hesap e-postaları
- [x] `AccountMailer` içinde token/mesaj gövdesi loglamasını kaldır.
- [x] Üretimde eksik veya test modunda posta yapılandırmasını açılışta reddet.
- [x] Hesap mesajları için kalıcı kuyruk, sınırlı retry ve teslimat durumunu uygula.
- [x] Kuyruktaki reset bağlantılarını sır olarak ele al; payload koruması, erişim ve
      kısa saklama süresi belirle. Yeni token üretiminde eski mesajı göndermeyi engelle.
- [x] Bilinen/bilinmeyen hesapların dışarıdan gözlenen yanıt ve limit davranışını eşitle.

### 1.3 Oturum iptali ve personel erişimi
- [x] Şifre sıfırlama ve tüm cihazlardan çıkış sonrası access/refresh token'ları iptal et.
- [x] Aynı saniyede üretilen token iptali yarışını gider; oturum sürümü yaklaşımını değerlendir.
- [x] Personelin takvimde aktifliğini üyelik erişiminden ayır; açık bir erişim kaldırma işlemi ekle.
- [x] Erişimi kaldırılmış personelin eski token ve refresh ile dönmesini engelle.
- [x] Bir işletmeden çıkarma diğer işletmelerdeki üyelikleri gereksiz yere kaldırmasın.

### 1.4 İstek sınırlaması
- [x] Login/register/reset için IP ve hesap bazlı sınırlar ekle; bilinmeyen hesapları da kapsa.
- [x] Nginx/Spring güvenilen proxy sınırını açıkça yapılandır; istemcinin sahte forwarded
      header ile limiti aşmasını engelle.
- [x] Rezervasyonun saatte 5 IP limitini ortak Wi-Fi ve farklı işletmeler açısından gözden geçir.
- [x] Redis kesintisi davranışı ve kullanıcıya tekrar deneme bilgisi tanımlı olsun.

Kabul: token loglarda yok; iptal edilen oturum hemen reddediliyor; çıkarılan personel
erişemiyor; iki farklı istemci proxy arkasında yanlışlıkla aynı kotayı paylaşmıyor;
sahte IP header'ı limiti aşamıyor. İlgili regresyon ve tenant izolasyonu testleri geçiyor.

## Aşama 2 — Hesap silme ve veri yaşam döngüsü (pilot engeli)

- [x] Hesap, randevu, booking request notu, outbox, bildirim, push, audit ve yedeklerde
      kişisel veri envanteri çıkar; hangi kopyanın silineceğini/korunacağını belirle.
- [x] Silme işlemine rezervasyon notları ve kuyruk alıcı/payload alanlarını dahil et.
- [x] Silmeyle yarışan rezervasyon, e-posta gönderimi ve onay işlemlerini güvenceye al.
- [x] Gelecek randevu ve bekleyen taleplerin hesap silinince ne olacağını açıkça uygula;
      müşteri onay ekranı ve işletme bildirimleri bu davranışla tutarlı olsun.
- [x] Dışa aktarma ve gizlilik ekranındaki vaatleri gerçek kapsama göre düzelt.
- [x] Yedekten dönüş sonrasında silinen hesap verilerinin tekrar görünmesini önleyecek
      silme kaydı/yeniden uygulama prosedürü oluştur.
- [x] Belge sürümü, bilgilendirme kaydı ve ayrı izin gerektiren işlemleri birbirinden ayır;
      ticari metinleri şirket/veri akışı bilgileriyle uzman değerlendirmesine hazırla.

Kabul: kişisel veri içeren sentetik müşteri birden çok işletmede silindiğinde hedeflenen
alanlarda veri kalmıyor; başka müşterilerin kayıtları değişmiyor; eski oturum kullanılamıyor;
bekleyen gönderimler kişisel veriyi tekrar üretmiyor. Bu teknik kabul hukuki uygunluk onayı değildir.

## Aşama 3 — Üretim benzeri ortam ve işletim (pilot engeli)

- [x] Sentetik verili staging, HTTPS ve production'dan ayrı anahtar/veritabanı kur.
- [x] CI'da yalnız frontend açılışı yerine backend+DB+Redis+Nginx smoke testi ekle.
- [x] Sürümlü imaj, ayrı migration adımı, health/readiness kontrolü ve dağıtım prosedürü ekle.
- [x] Runtime ortamından migration sahibi yetkilerini mümkün olduğunca ayır.
- [x] Yedek doğrulamasını değişen canlı DB yerine aynı snapshot'a ait manifestle yap.
- [x] Otomatik şifreli uzak yedek, başarısızlık alarmı ve güncel şemayla restore uygula.
- [x] API hata/gecikme, kuyruk yaşı, posta başarısızlığı ve disk/yedek alarmlarını bağla.
- [x] Önceki imaja dönüş ile şema/veri kurtarmayı ayrı ayrı prova et.

Kabul: sıfırdan ortam açılabiliyor; proxy üzerinden kayıt→rezervasyon→onay→iptal çalışıyor;
test alarmı ulaşıyor; yedekten açılan uygulamada kısıtlı DB rolüyle izolasyon ve smoke testi
geçiyor. RPO/RTO tatbikat sonucu kaydediliyor; ölçülmeden hizmet taahhüdü verilmiyor.

Bağımlılıklar: alan adı, hosting erişimi, posta göndereni, uzak yedek alanı ve alarm alıcısı.
Bunlar eksikse yerel yapılandırma/testler tamamlanır; dış ortam adımı bekliyor diye kaydedilir.

## Aşama 4 — Kolay kurulum ve pilot deneyimi

- [ ] Devam edilebilir kurulum: profil→hizmet/fiyat→personel→saatler→deneme randevusu→QR.
- [ ] Geçici şifre gösterimi yerine süreli, tek kullanımlık personel daveti oluştur.
- [ ] Mobil işletme panelinde Bugün, bekleyen talepler ve hızlı randevu işlemlerini öne çıkar.
- [ ] STAFF kendi programına erişecekse backend sahiplik kontrolüyle ayrı kapsam uygula.
- [ ] Liste rezervasyonunu 3D kapalıyken ve düşük donanımda eksiksiz tut.
- [ ] Gerçek Android/iPhone'da klavye, saat dilimi, yavaş ağ ve bağlantı kesilmesini dene.
- [ ] Dört ve üzeri çalışanı olan pilotta mevcut 3D koltuk sınırını liste akışıyla yönet;
      koltuk genişletmesini satış engeli yapma.
- [ ] Kurulum süresi, rezervasyon dönüşümü, onay süresi, bildirim başarısı ve destek
      taleplerini kişisel veriyi gereksiz toplamadan ölç.

Kabul: yeni işletme kurulumunu takip ederek ilk deneme rezervasyonunu alabiliyor;
müşteri mobilde oluşturma/taşıma/iptali tamamlıyor; pilotta kullanılan her rol işini yapabiliyor.

## Aşama 5 — Abonelik ve satışa hazırlık

- [ ] Tek ücretli paket ve deneme süresi için hak/kota tablosunu tanımla.
- [ ] Abonelik durumunu özellik haklarından ayır: deneme, aktif, ödeme gecikmesi,
      dönem sonunda iptal ve kısıtlı erişim davranışlarını belirle.
- [ ] Sağlayıcı seçimi sonrası hosted checkout ve doğrulanmış webhook uygula.
- [ ] Tekrarlı/sırasız webhook, tutar/para birimi, yanlış tenant eşlemesi ve mutabakatı test et.
- [ ] Başarısız ödemede ek süre ve uyarı sun; mevcut randevu/veri erişimini birden silme.
- [ ] Tahsilat/fatura referanslarını ve ilk müşteri faturalama iş akışını oluştur.
- [ ] Satış sayfası: somut fayda, demo rezervasyon, paket kapsamı, destek ve sözleşmeler.
- [ ] Yardım/iptal talebi/destek sürecini ve destek sorumlusunu tanımla.

Kabul: sandbox'ta başarılı/başarısız ödeme, yenileme, iptal ve olay tekrarı geçiyor;
tarayıcı dönüşü ödeme kanıtı sayılmıyor; demo yükseltme kaldırılıyor; tenant hakları doğru.
Canlı tahsilat provası sağlayıcı hesabı hazır olduğunda kontrollü ayrı adım olarak yürütülür.

Karar gerektiğinde: sağlayıcı, paket fiyatı, deneme/ek süre, şirket/fatura bilgileri.
Bu kararlar Aşama 1–4'teki bağımsız geliştirmeyi durdurmaz.

## Aşama 6 — Pilot ve kontrollü ücretli açılış

- [ ] 1–3 işletmede yaklaşık iki haftalık gözlem; en az 60 gerçek talep başlangıç hedefi.
- [ ] SMS ihtiyacı doğrulanırsa mevcut olay kuyruğuna sağlayıcı adaptörü, teslimat kaydı,
      telefon doğrulaması/normalizasyonu ve tenant başına maliyet sınırı ekle.
- [ ] Yeni kanal entegrasyonunda retry, taşınmış/iptal edilmiş randevu ve çift gönderim yarışını test et.
- [ ] Gerçekçi eşzamanlı müsaitlik sorgusu ve aynı randevu saatine yarış yük testi yap.
- [ ] Kritik/yüksek riskli açık bulguları kapat; güncel test/build sonuçlarını kaydet.
- [ ] Pilot geri bildirimlerini değerlendirip ilk 10 ücretli işletmeye kademeli aç.

Yayın kapısı: bilinen kritik/yüksek güvenlik sorunu yok; testler başarılı; çift rezervasyon
ve tenantlar arası veri erişimi testleri geçiyor; restore/alarm/posta kanıtı mevcut;
kurulum ve destek işliyor; abonelik ve fatura süreci doğrulanmış. Talep sayısı tek başına
yayın onayı değildir. Performans hedefleri ölçülen eşzamanlı yükle birlikte raporlanır.

## Çalışma düzeni ve ilk paket

Her paket sonunda değişiklikler, ilgili testler, kalan risk ve dış bağımlılıklar kaydedilecek.
Veritabanı değişikliklerinde eski migration değiştirilmeden yeni migration eklenecek.
Gerçek müşteri verisi/ücretli sağlayıcı gerektirmeyen geliştirmeler yerelde tamamlanacak.

**İlk uygulama paketi: 1.1 + 1.2'nin token loglama ve üretim posta denetimi bölümü.**
Önce hatalı saat dilimi testi düzeltilir; sonra token sızıntısı kaldırılır ve üretim ayarları
için regresyon testleri eklenir. Ardından hesap e-postası kuyruğu, oturum iptali,
personel erişimi ve rate limit sırasıyla ele alınır.

WhatsApp, kapora, çok şube, CSV içe aktarma, geniş 3D özelleştirme ve native mobil
uygulama ilk ücretli sürümün zorunlu kapsamına alınmaz; pilot talebiyle ayrı planlanır.
