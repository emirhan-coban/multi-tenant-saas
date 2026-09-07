# Multi-tenant SaaS

## Web uygulaması

PostgreSQL'i başlatıp backend'i çalıştırdıktan sonra:

```bash
cd web
npm install
npm run dev
```

Web istemcisi varsayılan olarak `http://localhost:8080` adresindeki API'ye bağlanır. Farklı bir API adresi için `VITE_API_URL` kullanılabilir.

Kayıt ve giriş ekranları, tenant üyeliklerini ve token yenilemeyi içerir. Access token bellekte tutulur; refresh token tarayıcı depolamasında kalır ve süresi dolan access token'lar Axios interceptor'ındaki tekil refresh kilidiyle yenilenir. Aynı anda gelen 401 yanıtları tek refresh isteğini bekler.
## Web veri ve yönlendirme altyapısı

- `/` → `/app`; `/login` giriş/kayıt ekranı, `/app` doğrulanmış oturum gerektiren kontrol panelidir. Bilinmeyen adreslerde 404 ekranı gösterilir.
- Aktif tenant ve rol `/api/me` üzerinden doğrulanır. İlk üyeliğin aktif tenant olduğu varsayılmaz. İşletme değiştirme arayüzü henüz yoktur.
- `web/src/query/client.ts`: ortak QueryClient, 30 saniye stale time, 5 dakika cache süresi. Okumalar geçici hatalarda en fazla iki kez tekrar edilir; 4xx ve mutation istekleri otomatik tekrarlanmaz.
- `web/src/query/keys.ts`: tenant bazlı sorgu anahtarları; çalışma saatleri ayrıca personel kimliğiyle ayrılır. Tenant anahtarı yalnızca cache ayrımı içindir; backend tenant'ı access token üzerinden belirler.
- `web/src/api/management.ts`: API fonksiyonları. `web/src/query/management.ts`: ekranlarda kullanılacak query/mutation hook'ları. Bu hook'lar doğrulanmış `TenantProvider` altında kullanılmalıdır.
- Query AbortSignal Axios'a aktarılır. Çıkış, bekleyen HTTP isteklerini (mutation ve refresh dahil) iptal eder ve tüm Query/Mutation cache'ini temizler. HTTP iptali, sunucunun önceden tamamladığı işlemi geri almaz.
- Davet hook'u geçici şifreyi callback üzerinden forma aktarır; mutation sonucunda saklamaz. Form sonuç kapandığında kendi yerel verisini temizlemelidir.
- Hizmet ekleme listede geçici kayıt gösterir; başarıda sunucu kaydıyla değiştirilir, hatada sadece ilgili geçici kayıt kaldırılır. Arka plan yenilemeleri bekleyen eklemeleri korur. Düzenleme ve silme başarıda cache'i günceller ve listeyi yeniden doğrular.

Kontroller:

```bash
cd web
npm test
npm run build
```

Production sunucusu, API ve statik dosya istekleri dışında SPA adreslerini (`/app`, `/login` vb.) `index.html` dosyasına yönlendirmelidir; doğrudan bağlantı ve sayfa yenileme buna bağlıdır. Vite geliştirme sunucusu bunu sağlar.

## Rol bazlı web paneli

Masaüstünde sol menü, 760px ve altında açılır menü kullanılır. Menü ve route guard aynı rol tanımlarına dayanır (`web/src/router/navigation.ts`). Rol, `/api/me` yanıtından alınır; tanınmayan roller panele alınmaz. Backend yetkilendirmesi bağımsız olarak korunur.

| Adres | Sayfa | Roller |
| --- | --- | --- |
| `/app` | Genel Bakış | OWNER, MANAGER, STAFF |
| `/app/services` | Hizmetler | OWNER, MANAGER |
| `/app/staff` | Personel | OWNER, MANAGER |
| `/app/working-hours` | Çalışma Saatleri | OWNER, MANAGER |
| `/app/appointments` | Randevular | OWNER, MANAGER |
| `/app/account` | Hesabım | OWNER, MANAGER, STAFF |

Hesabım mevcut üyelikleri ve aktif çalışma alanını görüntüler.

## Hizmetler ekranı

`/app/services` OWNER/MANAGER için liste, ekleme/düzenleme formu ve silme onayı içerir. Fiyatlar TRY olarak gösterilir; girişte nokta ve virgül ondalık ayracı kabul edilir. İlk yüklemede skeleton, boş listede ekleme çağrısı ve hatada tekrar deneme sunulur. Arka plan hatalarında mevcut liste korunur.

Ekleme sırasında modal kapanır ve listede “Kaydediliyor…” satırı belirir. Başarısız ekleme kaldırılır; aynı ekranda form taslağı tekrar denemek için tutulur. Taslak kalıcı depolamaya yazılmaz. Düzenleme/silme hatalarında modal açık kalır. Native dialog klavye odağını sınırlar; Escape ile kapanır ve odağı açan düğmeye döndürür (işlem sürerken kapanma engellenir).

## Personel ekranı

`/app/staff` OWNER/MANAGER için personel adı ve aktiflik durumunu listeler. Davet formu ad soyad ve e-posta alır; boş/geçersiz alanları ve bekleyen işlemin tekrar gönderilmesini engeller. Hatalarda form bilgileri korunur. İlk yüklemede skeleton, boş listede davet çağrısı ve liste hatasında tekrar deneme vardır.

`POST /api/staff/invite` mevcut backend davranışıyla personeli doğrudan oluşturur; e-posta göndermez. Sonuç penceresi e-postayı ve varsa geçici şifreyi gösterir. Mevcut hesaplar için şifre dönmediğinde mevcut şifreyle giriş bilgisi gösterilir. Kopyalama başarısızsa şifre alanından elle kopyalanabilir.

Geçici şifre yalnızca sonuç penceresinin yerel state'inde tutulur; Query/Mutation sonucuna, localStorage veya sessionStorage'a yazılmaz. Sonuç kapandığında veya sayfadan ayrılınca temizlenir. Mutasyon otomatik tekrar edilmez; başarılı oluşturma sonrasında liste yenilenir. Liste yenileme hatası oluşturma sonucunu silmez.

## Haftalık çalışma saatleri

`/app/working-hours`: personel seçimi, yedi gün için açık/kapalı ve saat seçicileri, seçilen günün saatlerini Pazartesi–Cuma'ya uygulama, tek kaydetme işlemi. Kapalı günlerde saatler gönderilmez. Kaydedilmemiş form arka plan yenilemelerinden korunur; personel değişiminde ve uygulama içi sayfa geçişlerinde onay istenir. Sayfa yenileme/kapatma tarayıcının yerel uyarısını kullanır. Kaydetme sırasında personel seçimi ve tekrar gönderim engellenir. Yönlendirme, bu kontroller için React Router data router üzerinden çalışır.

- `GET /api/working-hours?personelId=<uuid>`: aktif tenant içindeki personelin kayıtları.
- `PUT /api/working-hours`: tüm haftayı tek transaction içinde değiştirir; OWNER/MANAGER yetkisi gerekir.
- Gün sözleşmesi: **0=Pazartesi, 1=Salı, …, 6=Pazar**. `days` tam yedi benzersiz gün içermelidir.
- Açık gün: `closed: false`, `startTime` ve `endTime` zorunlu, başlangıç bitişten önce olmalı. Gece yarısını aşan aralıklar bu sürümde desteklenmez.
- Kapalı gün: `closed: true`, her iki saat `null`. Veritabanında o güne ait kayıt tutulmaz.
- Örnek gövde şekli: `{ "personelId": "<uuid>", "days": [{ "dayOfWeek": 0, "closed": false, "startTime": "09:00", "endTime": "18:00" }, ...diğer altı gün] }`.
- Yanıt, yalnızca açık günleri içeren mevcut `CalismaSaatiResponse[]` biçimindedir.
- Personel satırı üzerinde kilit, aynı personelin POST/PUT işlemlerini sıraya alır. Tekrar PUT, eski haftayı değiştirir ve kayıt çoğaltmaz. Eski POST endpoint'i, kayıtlı bir güne ikinci aralık eklemeyi reddeder (`DAY_ALREADY_EXISTS`).
- Önceden aynı güne birden fazla aralık girilmişse web ekranı sessizce aralık silmek yerine düzenlemeyi engeller. Eski veriler otomatik dönüştürülmez; haftalık PUT ile açıkça gönderilen plan eski aralıkları değiştirir.

PostgreSQL entegrasyon testleri Testcontainers ile otomatik oluşturulan ayrı, geçici bir veritabanında çalışır. Docker açık ve Java 21 kurulu olmalıdır:

```bash
./backend/mvnw -f backend/pom.xml test
```

Entegrasyon senaryoları tekrar kayıt, tüm günleri kapatma, bozuk hafta/saat doğrulaması, veritabanı hatasında rollback, eşzamanlı kayıt, tenant ayrımı ve HTTP rol kontrolünü kapsar. `HOURS_INTEGRATION` artık gerekmez; `contextLoads` dahil tüm Spring testleri geçici DB kullanır.

## Faz 3 — Randevular

### Takvim arayüzü

- Gün görünümünde personel sütunları, hafta görünümünde Pazartesi–Pazar sütunları ve renkli randevu blokları bulunur. Çizelge 09:00–18:00 ile başlar; dışarıdaki randevular için genişler.
- Üstte Hepsi/personel sekmeleri, önceki/sonraki dönem ve Bugün kontrolleri vardır.
- Yeni randevu penceresi hizmet → personel → tarih → saat butonları sırasını izler. Boş günde sonraki 30 günü tarayan, durdurulabilir en yakın gün araması vardır.
- Randevuya tıklayınca müşteri, telefon, hizmet, personel, not ve durum bilgileri açılır. Not düzenleme, tamamlandı/gelmedi/iptal ve taşıma desteklenir.
- Masaüstünde bloğu aynı personelin başka saatine veya hafta görünümünde başka güne sürükleyin. Backend'in sunduğu en yakın müsait saat onay penceresinde gösterilir. Personel değiştirme sürükleme ile yapılmaz. Mobil/klavye için detay penceresindeki Taşı aynı işlemi sağlar.
- TanStack Query mutation, randevuyu hemen soluk/pending blok olarak takvime ekler. Başarıda sunucu kaydıyla değiştirir; hatada yalnızca ilgili değişikliği geri alır. Aynı gövdeyle ağ hatası tekrarı aynı Idempotency-Key'i korur.
- SLOT_UNAVAILABLE / 409 açık bir saat doldu mesajı gösterir; müsaitlik ve takvim otomatik yenilenir. Eski sürüm çakışmaları ayrı mesajla gösterilir.
- Takvim tarayıcının saat dilimini, müsaitlik butonları işletmenin saat dilimini açıkça gösterir.

Son frontend doğrulaması: 53 test geçti (15 takvim/randevu testi dahil), production build başarılı. Ek takvim veya sürükleme kütüphanesi kullanılmadı.

`/app/appointments` sayfası müsait saat seçimi, oluşturma, günlük liste/personel filtresi, taşıma ve durum değişiklikleri sunar. PostgreSQL exclusion constraint tampon dahil çakışmayı engeller. Idempotency-Key yinelenen oluşturmayı, @Version eşzamanlı düzenlemede veri kaybını önler.

API örnekleri, tasarım kararları, saat dilimi sözleşmesi, kapsam sınırları ve Java kurmadan Docker ile test komutları: [Faz 3 teknik notları](docs/phase-3-appointments.md).
