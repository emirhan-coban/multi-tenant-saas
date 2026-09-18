# Metrikler ve alarmlar

`GET /actuator/metrics` ve `GET /actuator/metrics/{isim}` yalnızca
`Authorization: Bearer $APP_OPS_METRICS_TOKEN` ile çalışır. `GET /actuator/health` açıktır ve ayrıntı döndürmez.
Etiketlerde tenant, kullanıcı, e-posta, uç nokta gibi yüksek kardinaliteli/kişisel değerler yoktur.

| Metrik | Tür | Etiketler | Anlamı |
|---|---|---|---|
| `http.server.requests` | timer (histogram, SLO 100ms/300ms/1s/3s) | method, uri, status, outcome | Gecikme ve hata oranı |
| `saas.http.server_errors` | counter | method | 5xx yanıtlar |
| `saas.api.errors` | counter | code, status | İş kuralı hataları (`SLOT_UNAVAILABLE`, `FEATURE_DISABLED`…) |
| `saas.booking.decisions` | counter | decision, source | Talep kararları, 3D/liste dağılımı |
| `saas.trust.transitions` | counter | from, to, reason | Güven geçişleri (otomatik/manuel) |
| `saas.queue.pending` | gauge | queue=outbox, push, booking_approval, account_email | Bekleyen iş |
| `saas.queue.oldest_age_seconds` | gauge | queue | En eski bekleyen işin yaşı |
| `saas.abuse.events` | counter | kind=tenant_rate_limit, booking_ip_rate_limit, visit_code_failure, blocked_customer | Kötüye kullanım sinyali |
| `saas.push.deliveries` | counter | outcome=sent, retry, failed, dropped_gone | Push sağlığı |
| `saas.feature.disabled_hits` | counter | flag | Kapalı özelliğe gelen istek (eski sekmeler) |
| `saas.retention.deleted_rows` | counter | dataset | Saklama işinin sildiği satır |

## Pilot alarm eşikleri (başlangıç önerisi)

| Alarm | Koşul | Eylem |
|---|---|---|
| API gecikmesi | `http.server.requests` p95 > 1 s, 10 dk | Yavaş uçları incele; 3D yükse `booking.3d` kapat |
| 5xx | 5 dk'da > 5 veya oran > %1 | Log `traceId` ile incele; gerekirse sürümü geri al |
| Onay kuyruğu yaşlanıyor | `oldest_age_seconds{queue=booking_approval}` > 600 (çalışma saatinde) | İşletmeyi ara; push çalışıyor mu kontrol et |
| Push kuyruğu | `pending{queue=push}` > 100 veya yaş > 300 s | Dispatcher/VAPID/servis erişimi |
| Push kalıcı hata | `push.deliveries{outcome=failed}` 1 saatte > %20 | VAPID anahtarı değişmiş olabilir |
| Ziyaret kodu kaba kuvvet | `abuse.events{kind=visit_code_failure}` 10 dk'da > 20 | Personel hesabını ve tenant'ı incele |
| Rezervasyon spam | `abuse.events{kind=booking_ip_rate_limit}` 1 saatte > 50 | IP bazlı engel, gerekirse işletmede otomatik onayı kapat |
| Outbox | `pending{queue=outbox}` > 200 | E-posta sağlayıcısı |
| Hesap e-postası | `pending{queue=account_email}` > 20 veya yaş > 120 s | SMTP ayarı, şifreleme anahtarı ve dispatcher |
| Saklama işi | 26 saattir yeni `data_retention_runs` satırı yok | Scheduler/ShedLock kilidi |

Prometheus kullanılacaksa `io.micrometer:micrometer-registry-prometheus` bağımlılığı eklenip
`management.endpoints.web.exposure.include` içine `prometheus` yazılır; aynı token koruması geçerlidir.
