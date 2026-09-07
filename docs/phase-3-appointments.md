# Faz 3 — Randevu çekirdeği

## 1. Kullanım

Uygulama: http://localhost:5173/app/appointments
Backend: http://localhost:8081

İşletme sahibi veya yönetici hesabıyla giriş yapın. Önce Hizmetler ekranında süre ve ara süresi tanımlayın, Personel ekranında çalışan ekleyin, Çalışma Saatleri ekranında haftayı kaydedin. Randevular sayfasında tarihi, personeli ve hizmeti seçince boş saatler gelir. Müşteri adı, telefon ve not isteğe bağlıdır.

Randevu listesinde personel filtresi, taşıma, iptal, tamamlandı ve gelmedi işlemleri bulunur. Gelecekteki randevu henüz tamamlandı/gelmedi yapılamaz. Taşıma için yeni tarih ve müsait saat seçilir. Eski sürümle işlem yapılırsa kullanıcıya listeyi yenilemesi söylenir.

Mevcut Docker kurulumu için proje kökünde:

```bash
docker compose up -d
docker start saas_backend saas_frontend
```

Backend kodunu değiştirdikten sonra:

```bash
docker restart saas_backend
```

Compose şu anda PostgreSQL ve Redis'i içerir; backend/frontend önceki kurulumdaki ayrı konteynerlerdir. Bu iki konteyner silinmişse `docker start` onları yeniden oluşturmaz.

## 2. Migration ve veri modeli

`backend/src/main/resources/db/migration/V5__appointments.sql`

- appointments: tenant, personel, hizmet, başlangıç/bitiş, durum, not, müşteri adı/telefonu ve version.
- customer_id nullable tutuldu; bu fazda customers tablosu veya müşteri CRUD yok.
- duration_min ve buffer_min randevu oluşturulurken hizmetten kopyalanır.
- ends_at hizmetin bittiği an; busy_until tampon sürenin de bittiği andır.
- Hizmet daha sonra düzenlense bile mevcut randevu ve taşıma işlemi kendi süre kopyasını kullanır.
- Bileşik foreign key'ler personel ve hizmetin randevuyla aynı tenant'a ait olmasını zorunlu tutar.
- RLS hem okuma hem yazma için tenant kontrolü içerir.

## 3. Çakışma garantisi

`no_overlap`, aynı personelin `[starts_at, busy_until)` aralıklarının kesişmesini PostgreSQL GiST exclusion constraint ile engeller. Üst sınır hariçtir: 09:00–09:40 doluluğunun hemen ardından 09:40'ta yeni randevu başlayabilir.

CANCELLED dışındaki durumlar zamanı kapatır. İptal saati serbest bırakır; COMPLETED ve NO_SHOW geçmiş kayıtlarda çakışma garantisini korur.

Önce uygulama müsaitliği kontrol eder; eşzamanlı işlemler bu kontrolü birlikte geçerse son kararı veritabanı verir. SQLSTATE 23P01, HTTP 409 / SLOT_UNAVAILABLE olarak döner. Garanti tüm uygulama örnekleri için geçerlidir; bellekte kilit kullanılmaz.

## 4. Saf müsaitlik fonksiyonu

`appointment/MusaitlikHesaplayici.java` herhangi bir repository, HTTP, saat dilimi veya sistem saati kullanmaz.

1. Çalışma aralıklarını sıralar ve birleşen/bitişik aralıkları birleştirir.
2. İzin ve mevcut randevu doluluklarını birleştirir.
3. Engelleri çalışma aralıklarından çıkarır.
4. Her boşluğun başlangıcından itibaren hizmet + tampon kadar ilerler.
5. Kullanıcıya hizmetin gerçek başlangıç/bitişini döner; tampon da boşluğa tamamen sığmalıdır.

Bu sürümde bağımsız bir 5/15 dakikalık slot adımı yoktur; adım hizmet + tampon süresidir. API yalnızca hesaplanan slotların başlangıçlarını kabul eder. Gün sonunda tampon için yer yoksa o randevu sunulmaz.

## 5. Idempotency

POST için Idempotency-Key zorunludur. Anahtar 1–128 karakter; harf/rakam ve . _ : - kabul edilir.

- Kapsam: tenant + kullanıcı + anahtar.
- JSON istek içeriğinin SHA-256 özeti saklanır.
- Aynı anahtar/aynı gövde: kayıt anındaki JSON yanıt ve HTTP 201 tekrar döner.
- Aynı anahtar/farklı gövde: HTTP 409 / IDEMPOTENCY_CONFLICT.
- Anahtar için PostgreSQL transaction advisory lock kullanılır. Yinelenen istek ilk transaction'ın bitmesini bekler.
- Randevu ve yanıt aynı transaction'da kaydedilir. Başarısız işlem anahtarı tüketmez.
- Randevu sonradan iptal edilse de tekrar istek ilk yanıtı döner.
- Anahtarlar bu fazda otomatik zaman aşımına uğratılmaz. Gelecekte temizlik eklenirse idempotency süresi API sözleşmesinde açıkça belirtilmelidir.

Frontend, aynı formun ağ hatasından sonra tekrar gönderiminde aynı anahtarı kullanır. Anahtar ve gövde sekmeye ait sessionStorage'da tenant/kullanıcı bazında tutulur; başarılı yanıtta temizlenir. Form değişirse yeni anahtar üretilir. Bekleyen işlem sırasında tekrar gönderim ayrıca engellenir.

## 6. Düzenleme ve durum geçişleri

Entity'de `@Version Integer version` bulunur. PATCH mutlaka istemcinin gördüğü version değerini gönderir.

İlk kontrol eski istemci sürümünü reddeder; JPA'nın version koşullu UPDATE'i, iki işlem aynı sürümü birlikte okuduğunda da ikinci yazmayı engeller. Çakışma HTTP 409 / STALE_VERSION'dır.

İzin verilen geçişler:

- CONFIRMED → CANCELLED
- CONFIRMED → COMPLETED
- CONFIRMED → NO_SHOW

Son durumlar değiştirilemez. Taşıma yalnızca CONFIRMED randevuda yapılır. Taşıma ve sonuçlandırma aynı PATCH'e konulamaz. Not gönderilmezse korunur; boş metinle temizlenebilir.

## 7. Saat dilimi

Veritabanı timestamptz, Java Instant ve API ISO-8601 UTC kullanır. Haftalık yerel saatler tenant.timezone ile gerçek zaman aralıklarına çevrilir. Eski Istanbul değeri Europe/Istanbul olarak yorumlanır. Yaz saati geçişlerinde 23/25 saatlik gün ve atlanan saatler dikkate alınır.

Müsaitlikte date işletmenin yerel tarihidir. Yanıt timezone bilgisini içerir ve slot seçicisindeki saatler bu saat dilimiyle gösterilir. Günlük liste mevcut frontend yaklaşımıyla tarayıcının yerel gününe göre from/to gönderir ve tarayıcı saat dilimini açıkça gösterir. İşletme ve tarayıcı farklı saat dilimlerindeyse bu iki gün sınırı farklı olabilir.

Hafta: 0=Pazartesi ... 6=Pazar. Gece yarısını aşan vardiyalar Faz 2 çalışma saatleri sözleşmesinde desteklenmez.

## 8. API sözleşmesi

Tüm yeni uç noktalar Bearer token ve OWNER/MANAGER rolü ister. Tenant istemci gövdesinden alınmaz.

| İşlem | Adres | Girdi |
| --- | --- | --- |
| Oluştur | POST /api/appointments | Idempotency-Key ve Create gövdesi |
| Listele | GET /api/appointments | from, to; staffId isteğe bağlı |
| Düzenle | PATCH /api/appointments/{id} | version; startsAt/status/notes isteğe bağlı |
| Müsaitlik | GET /api/availability | staffId, serviceId, date; taşımada appointmentId isteğe bağlı |

Liste aralığı 0'dan büyük, en fazla 93 gündür; aralıkla kesişen randevular döner. İptal kayıtları listede kalır.

POST gövdesi:

```json
{
  "staffId": "<personel-uuid>",
  "serviceId": "<hizmet-uuid>",
  "startsAt": "2030-01-07T06:00:00Z",
  "customerName": "Örnek müşteri",
  "customerPhone": "",
  "notes": "İlk ziyaret"
}
```

PATCH:

```json
{ "version": 0, "status": "CANCELLED" }
```

Taşımada availability'ye appointmentId verilmesi eski randevunun kendi zamanını engel olmaktan çıkarır ve eski süre/tampon kopyasını kullanır. Başka tenant'ın randevusu veya uyuşmayan personel/hizmet reddedilir.

## 9. Testlerin çalıştırılması

Docker açık, Java 21 kuruluysa:

```bash
cd backend
./mvnw test
```

Java kurmadan, bu bilgisayardaki Docker/Maven düzeniyle proje kökünde:

```bash
docker run --rm --name saas_phase3_tests \
  -v "$PWD/backend:/workspace" \
  -v saas_maven_cache:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9-eclipse-temurin-21 \
  ./mvnw -B -ntp test
```

Testcontainers PostgreSQL 16 açar; tüm Spring testleri geçici DB kullanır. Ryuk test konteynerlerini temizler. Geliştiricinin saasdb'si testler tarafından değiştirilmez. Docker yoksa testler sessizce atlanmak yerine başarısız olur.

Frontend:

```bash
docker exec saas_frontend npm test
docker exec saas_frontend npm run build
```

Son doğrulama:

- Backend: 49 test geçti, 0 başarısız, 0 atlanan.
- 23 saf müsaitlik testi.
- 18 PostgreSQL/API entegrasyon testi.
- 7 mevcut çalışma saatleri testi ve 1 uygulama açılış testi.
- 50 eşzamanlı HTTP isteği: 1 adet 201, 49 adet 409.
- 8 eşzamanlı aynı anahtar: 8 başarılı aynı yanıt, tek kayıt.
- Uygulama kontrolünü atlayan SQL insert çakışması da veritabanınca reddedildi.
- Tenant izolasyonu ve superuser olmayan rol ile RLS okuması doğrulandı.
- Frontend: 47 test geçti; bunların 9'u yeni randevu ekranı testidir.
- Frontend production build başarılı.
- Çalışan uygulamada migration v5 uygulandı; backend health UP.

## 10. Kapsam kararları

Bu faz yönetim panelinden personel adına randevu oluşturur; halka açık müşteri rezervasyonu veya STAFF self-service eklenmedi. Aynı işletmedeki aktif personel ve silinmemiş hizmetler kullanılabilir; staff_services için ayrı bir yetenek atama ekranı/sözleşmesi mevcut olmadığından hizmet-personel yetenek kısıtı uygulanmıyor.

Müsaitlik o anki çalışma saatleri ve izinlerle hesaplanır. Daha sonra çalışma saatlerini değiştirmek veya izin eklemek mevcut randevuları otomatik taşımaz/iptal etmez. Randevu-randevu çakışma garantisi veritabanındadır; vardiya/izin düzenlemesiyle randevu oluşturmayı tek bir ortak kilitte sıralama bu sürümün kapsamına alınmadı.

Mevcut geliştirme veritabanı admin hesabı superuser olduğu için RLS tek başına yeterli kabul edilmedi: yeni randevu sorguları ve formun personel/hizmet listeleri açık tenant filtresi kullanır. Üretimde ayrı migration ve uygulama rolleri tasarlanmalıdır.

Mülakatta anlatılacak temel ayrım: saf fonksiyon kullanıcıya uygun saatleri sunar; PostgreSQL exclusion constraint yarış durumunda son garantiyi sağlar; idempotency aynı işlemin tekrarını, @Version ise eşzamanlı düzenleme kaybını önler.

