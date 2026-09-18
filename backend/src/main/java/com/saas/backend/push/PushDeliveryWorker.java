package com.saas.backend.push;

import com.saas.backend.ops.OpsMetrics;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PushDeliveryWorker {
    static final int BATCH_SIZE = 50;
    static final int MAX_ATTEMPTS = 5;
    static final int DISABLE_AFTER_CONSECUTIVE_FAILURES = 10;
    private final JdbcTemplate db;
    private final PushTransport transport;
    private final WebPushConfiguration.WebPushKeys keys;
    private final ObjectMapper json;
    private final OpsMetrics metrics;

    public PushDeliveryWorker(JdbcTemplate db, PushTransport transport, WebPushConfiguration.WebPushKeys keys,
                              ObjectMapper json, OpsMetrics metrics) {
        this.db = db;
        this.transport = transport;
        this.keys = keys;
        this.json = json;
        this.metrics = metrics;
    }

    /** Bir parti teslimatı işler; işlenen satır sayısını döndürür. Birden fazla örnek {@code skip locked} ile çakışmaz. */
    @Transactional
    public int processBatch() {
        if (!keys.configured()) return 0;
        var rows = db.queryForList("""
                select d.id,d.subscription_id,d.event_type,d.title,d.body,d.url,d.attempts,
                       s.endpoint,s.p256dh,s.auth_secret,s.disabled_at
                from push_deliveries d join push_subscriptions s on s.id=d.subscription_id
                where d.status='PENDING' and d.available_at<=now()
                order by d.available_at,d.id limit ? for update of d skip locked
                """, BATCH_SIZE);
        for (var row : rows) deliver(row);
        return rows.size();
    }

    private void deliver(Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        UUID subscription = (UUID) row.get("subscription_id");
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        if (row.get("disabled_at") != null) {
            finish(id, "DROPPED", attempts - 1, null);
            metrics.pushDelivery("dropped_gone");
            return;
        }
        int status;
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("title", row.get("title"));
            payload.put("body", row.get("body"));
            payload.put("url", row.get("url"));
            payload.put("tag", row.get("event_type"));
            byte[] body = keys.crypto().encrypt((String) row.get("p256dh"), (String) row.get("auth_secret"),
                    json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            String endpoint = (String) row.get("endpoint");
            status = transport.send(endpoint, Map.of(
                    "TTL", "86400",
                    "Urgency", "normal",
                    "Content-Encoding", "aes128gcm",
                    "Content-Type", "application/octet-stream",
                    "Authorization", keys.crypto().vapidAuthorization(endpoint, keys.subject(), Instant.now())), body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            retry(id, subscription, attempts, null);
            return;
        } catch (java.io.IOException | RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Push delivery transport error deliveryId={} attempt={} error={}", id, attempts, ex.getClass().getSimpleName());
            retry(id, subscription, attempts, null);
            return;
        }
        if (status >= 200 && status < 300) {
            finish(id, "SENT", attempts, status);
            db.update("update push_subscriptions set last_success_at=now(),failure_count=0,updated_at=now() where id=?", subscription);
            metrics.pushDelivery("sent");
        } else if (status == 404 || status == 410) {
            // Tarayıcı aboneliği kaldırılmış veya süresi dolmuş: bir daha denenmez.
            finish(id, "DROPPED", attempts, status);
            db.update("update push_subscriptions set disabled_at=now(),disabled_reason='GONE_' || ?,updated_at=now() where id=? and disabled_at is null",
                    status, subscription);
            db.update("update push_deliveries set status='DROPPED',finished_at=now() where subscription_id=? and status='PENDING' and id<>?",
                    subscription, id);
            metrics.pushDelivery("dropped_gone");
        } else if (status == 429 || status >= 500) {
            retry(id, subscription, attempts, status);
        } else {
            // 400/401/403/413: istek kalıcı olarak hatalı (ör. VAPID anahtarı değişti); abonelik hatası sayılır.
            finish(id, "FAILED", attempts, status);
            countFailure(subscription, status);
            metrics.pushDelivery("failed");
        }
    }

    private void retry(UUID id, UUID subscription, int attempts, Integer status) {
        if (attempts >= MAX_ATTEMPTS) {
            finish(id, "FAILED", attempts, status);
            countFailure(subscription, status);
            metrics.pushDelivery("failed");
            return;
        }
        long delaySeconds = Math.min(3600, 30L * (1L << (attempts - 1)));
        db.update("update push_deliveries set attempts=?,last_status=?,available_at=now()+(? * interval '1 second') where id=?",
                attempts, status, delaySeconds, id);
        metrics.pushDelivery("retry");
    }

    private void countFailure(UUID subscription, Integer status) {
        db.update("""
                update push_subscriptions set failure_count=failure_count+1,updated_at=now(),
                  disabled_at=case when failure_count+1>=? then now() else disabled_at end,
                  disabled_reason=case when failure_count+1>=? then 'REPEATED_FAILURES' else disabled_reason end
                where id=? and disabled_at is null
                """, DISABLE_AFTER_CONSECUTIVE_FAILURES, DISABLE_AFTER_CONSECUTIVE_FAILURES, subscription);
    }

    private void finish(UUID id, String status, int attempts, Integer httpStatus) {
        db.update("update push_deliveries set status=?,attempts=?,last_status=?,finished_at=now() where id=?", status, attempts, httpStatus, id);
    }
}
