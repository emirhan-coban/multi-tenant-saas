# Faz 4 — Operasyon, güvenlik ve raporlama

## Tamamlanan kapsam

- FREE: 2 aktif personel, işletmenin yerel takvim ayı içinde 100 yeni randevu, dakikada 60 doğrulanmış API isteği.
- PRO: personel ve aylık randevu kotası yok; dakikada 300 istek.
- Bucket4j 8.16.0 + Lettuce/Redis: aynı işletmenin farklı kullanıcıları ve backend instance'ları ortak limiti paylaşır.
- Değiştirilemeyen işlem geçmişi; tarih, aktör ve sayfa filtreleri.
- Transactional outbox, konsol hatırlatmaları, yeniden deneme, ShedLock 7.7.0.
- JSON uygulama logları, tenantId/traceId, yanıt üzerinde X-Trace-Id.
- Ciro / randevu / popüler hizmet raporu, personel doluluk hesabı.
- Dashboard kullanım çubukları; rapor, geçmiş, plan ve bildirim sayfaları.
- Personel pasifleştirme; quota/429 hatalarında anlaşılır mesaj ve demo yükseltme bağlantısı.

## Çalıştırma

Proje kökünde mevcut .env dosyası korunmalıdır. İlk kurulumda .env.example değerlerini .env dosyasına aktarın.

```bash
docker compose --profile app up -d
docker compose --profile app logs -f backend
```

- Frontend: http://localhost:5173/app
- Backend health: http://localhost:8081/api/health
- Yalnızca Postgres/Redis: docker compose up -d
- Uygulamayı durdurma: docker compose --profile app stop
- Kod değişiminde backend: docker compose --profile app restart backend
- Frontend Vite hot reload kullanır.
- Verileri silen down -v komutunu kullanmayın.
- Docker geliştirme profili JDK/Node kurulumunu gerektirmez; ilk çalıştırma bağımlılıkları indirir.
- Docker dışında backend çalıştırılırsa Redis localhost:6379 varsayılanını kullanır. APP_REDIS_URL ile değiştirilebilir. Compose içinde redis://redis:6379 gönderilir.
- Önceki elle başlatılmış uygulama container'ları, bu bilgisayarda saas_backend_before_phase4 ve saas_frontend_before_phase4 adlarıyla durdurulmuş olarak korundu. Veri volume'ları değiştirilmedi. Bunlar kod yedeği değildir; aynı proje bind mount'unu kullanırlar.

## API sözleşmesi

Tüm aşağıdaki istekler access token gerektirir. Tenant, istemci gövdesinden değil doğrulanmış oturumdan alınır.

| Endpoint | Yetki | İşlev |
| --- | --- | --- |
| GET /api/usage | OWNER, MANAGER | Plan, iki kota, aylık dönem, saat dilimi, istek limiti |
| GET /api/audit-log?from=&to=&actorId=&page= | OWNER, MANAGER | İşlem geçmişi; sayfa başına 50 |
| GET /api/audit-log/actors | OWNER, MANAGER | Sadece işletmenin geçmişindeki aktörler |
| GET /api/reports/summary?from=&to= | OWNER, MANAGER | Randevu sayıları, ciro, hizmet dağılımı |
| GET /api/reports/staff-utilization?from=&to= | OWNER, MANAGER | Aktif personel çalışma/dolu dakika ve yüzde |
| GET /api/notification-preferences | Üyeler | Aktif tenant + kullanıcı tercihi |
| PATCH /api/notification-preferences | Üyeler | {"remindersEnabled": false} |
| DELETE /api/staff/{id} | OWNER, MANAGER | Personeli pasifleştirme; geçmişi korur |

from/to ISO-8601 UTC instant olmalıdır. Aralık [from,to), en fazla 93 gündür. Frontend bitiş gününü dahil etmek için sonraki günün başlangıcını gönderir; kullanılan tarayıcı saat dilimini ekranda gösterir. Kullanım ayı ise tenant'ın saat dilimine göre hesaplanır.

Örnek quota yanıtı:

```json
{
  "code": "QUOTA_EXCEEDED",
  "message": "Aylık randevu kotanız doldu. Planınızı yükseltin.",
  "details": {
    "resource": "appointments",
    "limit": 100,
    "used": 100,
    "resetsAt": "2026-09-30T21:00:00Z"
  }
}
```

HTTP 403 döner. Yetki hatasından code alanıyla ayrılır. 429 yanıtta Retry-After saniye cinsindendir. Redis kontrolü kullanılamazsa istek sınırsız bırakılmaz: 503 RATE_LIMIT_UNAVAILABLE ve Retry-After: 5 döner. Frontend yazma isteklerini otomatik tekrarlamaz.

## Kota ve eşzamanlılık

OperationsService.checkQuota, tenant başına PostgreSQL transaction advisory lock alır. Kullanımı sayma ve kayıt ekleme aynı transaction içindedir. Böylece farklı backend instance'ları da aynı kilidi paylaşır.

- Personel: active=true satırları sayılır.
- Randevu: created_at ilgili yerel ay içinde olan tüm satırlar sayılır; randevunun gerçekleşeceği ay değil, oluşturulduğu ay önemlidir.
- İptal aylık kotayı geri vermez; aksi halde sınırsız oluştur/iptal döngüsü mümkün olurdu.
- Başarısız işlem kotayı tüketmez.
- Idempotency kaydı kota kontrolünden önce okunur; dolu kotada dahi aynı isteğin tekrarı önceki cevabı döner.
- Plan veritabanından okunur. Tarayıcıdan plan değiştirme/ödeme endpoint'i yoktur.
- Personeli pasifleştirmek kota açar ama kullanıcı hesabını veya üyeliğini kaldırmaz. Mevcut randevular korunur.
- Önceden FREE sınırını aşan veri varsa otomatik silinmez; yeni ekleme engellenir.

## Redis rate limiting

Her tenant/plan kapasitesi için Redis anahtarı tutulur. Interval refill ilk bucket oluşumundan itibaren bir dakikada kapasiteyi yeniler. Bu bir sliding-window sayacı değildir. Farklı tenant'lar birbirinin limitini tüketmez. Anahtarlar dolum süresi + ek TTL ile temizlenir.

Anonim kayıt/giriş istekleri bu tenant bazlı limiter kapsamında değildir. Production için ayrıca IP tabanlı kimlik doğrulama koruması ve edge proxy limitleri gerekir. Redis restart/veri kaybı bucket durumunu sıfırlar; production Redis dayanıklılığı ayrı operasyon konusudur.

## İşlem geçmişi

V6 migration audit_log ekler. UPDATE, DELETE ve TRUNCATE trigger ile reddedilir; API üzerinden kayıt değiştirme/silme yolu sunulmaz. Tenant RLS policy'si uygulanır.

Kaydedilen başlıca olaylar:
- Randevu oluşturma, taşıma, iptal, tamamlanma, gelmedi, not güncellemesi.
- Personel davet ve pasifleştirme.
- Hizmet fiyat değişimi (eski/yeni fiyat) ve hizmet silme.

İşlem ve audit aynı transaction içinde commit olur; rollback olmuş işleme ait yanıltıcı geçmiş kalmaz. Kullanıcı notları, telefonlar veya şifreler audit detayına yazılmaz. Aktör kimliği kalıcıdır; görünen ad users tablosundan okunur. Geçmişe dönük eski işlemler uydurulmaz; kayıtlar Faz 4 sonrasında oluşur.

DDL yetkili veritabanı yöneticisi trigger'ı değiştirebilir. Bu çözüm yöneticiye karşı harici WORM/arşiv garantisi değildir.

## Bildirim outbox'ı

Randevu oluşturma transaction'ı bir APPOINTMENT_REMINDER kaydı ekler. Alıcı randevuyu oluşturan kullanıcıdır; müşteriye veya tüm personele otomatik mesaj gönderilmez.

- available_at: başlangıçtan 24 saat önce; bu zaman geçmişse ilk job çalışmasında.
- Job: yaklaşık dakikada bir, başlangıçtan bir dakika sonra.
- ShedLock: veritabanı saatini kullanan dağıtık kilit.
- İşleme: tenant başına transaction, en fazla 100 uygun kayıt, FOR UPDATE SKIP LOCKED.
- Başarı: SENT + sent_at.
- İptal/sonuçlanma, süresi geçmiş randevu, eski başlangıç saati veya kapalı tercih: SKIPPED.
- Taşıma: bekleyen eski hatırlatma iptal edilir ve yeni başlangıca göre yenisi oluşturulur.
- Gönderim hatası: artan bekleme ile tekrar; beşinci başarısızlıkta FAILED.
- Bildirim tercihi gönderim anında tekrar okunur; kullanıcı + tenant bazındadır.
- Gönderim adaptörü sadece JSON konsol logu üretir. E-posta/SMS/push yoktur.
- Logda eventId bulunur; ileride gerçek sağlayıcıya idempotency/deduplication anahtarı olarak gönderilmelidir.

Garanti at-least-once tasarımıdır: dış sağlayıcı mesajı aldıktan hemen sonra process ölür ve transaction commit olmazsa tekrar gönderim olabilir. ShedLock tek başına exactly-once sağlamaz. Gerçek sağlayıcı eklendiğinde eventId tabanlı deduplication, timeout, FAILED kayıt izleme ve yeniden işleme ekranı gerekir. LockAtMostFor 5 dakikadır; uzun işler için süre/heartbeat ayarı gereklidir.

## Loglama

Spring Boot'un logstash JSON formatter'ı kullanılır; mesaj ve MDC alanlarını güvenli biçimde escape eder. TraceFilter her istek için yeni UUID üretir. JWT doğrulanınca tenantId MDC'ye eklenir. İstek sonunda HTTP yöntem/durum/süre kaydı yazılır ve MDC finally içinde temizlenir. Scheduler kendi tenant/trace bağlamını kurup temizler. Token, parola, istek gövdesi veya müşteri bilgisi loglanmaz.

Maven/Spring açılış banner'ı veya üçüncü parti JVM çıktıları JSON olmak zorunda değildir; uygulamanın logging çıktıları JSON'dur.

## Raporlama tanımları

- Toplam randevu: başlangıç zamanı aralıkta olan tüm randevular, iptaller dahil.
- Ciro: sadece COMPLETED randevuların price_amount toplamı; ödeme tahsilatı değildir.
- Yeni randevu hizmet fiyatını snapshot olarak alır. Sonradan hizmet fiyatı değişse de geçmiş ciro değişmez.
- Eski randevularda tarihsel fiyat bilinmez: nullable bırakılır, unknownPriceCount ile ekranda uyarılır ve ciroya dahil edilmez.
- Hizmet popülerliği: iptal edilmemiş randevular; eşit sayıda hizmet ad sırasına göre seçilir.
- Doluluk: aktif personelin haftalık saatleri tenant timezone ile gerçek instant'a çevrilir. Postgres multirange çalışma aralıklarını birleştirir, izinleri çıkarır, rapor aralığına kırpar. İptal edilmemiş randevu + tampon süresinin bu kapasiteyle kesişimi dolu dakikayı verir.
- Çakışan izin/çalışma aralıkları iki kez sayılmaz; sıfır kapasite %0 döner. DST'de çöken/terslenen yerel aralıklar atlanır.
- Haftalık çalışma saatlerinin geçmiş versiyonları yoktur; geçmiş doluluk bugünkü haftalık tanım üzerinden hesaplanır.
- Grafikler ek bağımlılık olmadan React/CSS ile oluşturuldu; sayısal etiketler ve erişilebilir tablo/listeler de bulunur.

## Ekranlar

- /app: kullanım kartı (yönetim).
- /app/plan: FREE/PRO kapasite karşılaştırması ve demo yükseltme açıklaması.
- /app/reports: tarih aralığı, kartlar, hizmet ve personel bar grafikleri.
- /app/audit-log: tarih/aktör filtresi, detay açma, sayfalama.
- /app/notifications: kişisel hatırlatma tercihi.
- /app/staff: onaylı pasifleştirme.

STAFF yönetim raporlarını/geçmişi/kotayı göremez. Bu faz, çalışanların kendi müşterilerini veya randevularını göreceği ayrı personel panelini eklemez.

## Testler

Son doğrulama: **78 backend testi, 67 frontend testi geçti**; frontend production build ve git diff --check başarılı. Yerel V6 migration, dört Docker servisi ve canlı ekranlar ayrıca kontrol edildi.

Backend Testcontainers ile gerçek Postgres 16 ve Redis 7 kullanır; geliştirme veritabanını kullanmaz.

```bash
docker run --rm \
  -v "$PWD/backend:/workspace" \
  -v saas_maven_cache:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9-eclipse-temurin-21 ./mvnw -B -ntp test

docker compose --profile app exec frontend npm test
docker compose --profile app exec frontend npm run build
git diff --check
```

Docker host override yukarıda Docker Desktop içindir. Linux ortamında Testcontainers host ayarı kendi Docker kurulumuna göre seçilmelidir.

Test kapsamı: mevcut Faz 3 regresyonları; 50 paralel davette kota; son aylık randevu kotasında yarış; idempotency replay; ay sınırı; PRO; gerçek Redis atomik limit; 429 ve 403 hata sözleşmesi; Redis kesintisinde fail-closed; rol/tenant/RLS izolasyonu; immutable audit; transaction rollback; aynı outbox kaydının tekrar/paralel işlenmesi; opt-out/iptal/taşıma; retry/FAILED; ShedLock; fiyat snapshot; bilinmeyen eski fiyat; izin/tampon/DST doluluk; JSON escaping ve MDC temizliği. Frontend testleri yeni ekranlar, tercihler, aralık doğrulama, quota/429 ve rol menülerini içerir.

## Production sınırları

Bu profil geliştirme içindir: kaynak mount'u, Vite dev server ve Maven run kullanır. Örnek DB/JWT ayarları production sırrı olarak kullanılmamalıdır. Mevcut admin bağlantısı superuser olduğundan RLS'yi bypass eder; yeni tabloların RLS davranışı testte kısıtlı rolle doğrulanmıştır. Production için ayrı migration/runtime DB rolleri ve tüm mevcut modüllerin transaction içi tenant bağlamı ayrıca gözden geçirilmelidir. Bu faz production deployment, gerçek ödeme, müşteri e-posta/SMS sağlayıcısı veya çalışan portalı olarak sunulmaz.
