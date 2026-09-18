package com.saas.backend.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Kuyruk derinliği ve en eski bekleyen işin yaşı. Veritabanı sorgusu metrik kazıma isteğinden ayrılmıştır. */
@Component
public class QueueDepthMetrics {
    static final List<String> QUEUES = List.of("outbox", "push", "booking_approval", "account_email");
    private final JdbcTemplate db;
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> oldest = new ConcurrentHashMap<>();

    public QueueDepthMetrics(JdbcTemplate db, MeterRegistry registry) {
        this.db = db;
        for (String queue : QUEUES) {
            pending.put(queue, new AtomicLong());
            oldest.put(queue, new AtomicLong());
            Gauge.builder("saas.queue.pending", pending.get(queue), AtomicLong::get).tag("queue", queue).register(registry);
            Gauge.builder("saas.queue.oldest_age_seconds", oldest.get(queue), AtomicLong::get).tag("queue", queue).register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${app.metrics.queue-interval-ms:30000}", initialDelayString = "${app.metrics.queue-initial-delay-ms:15000}")
    public void refresh() {
        try {
            for (var row : db.queryForList("select queue_name,pending,oldest_age_seconds from ops_queue_depth()")) {
                String queue = (String) row.get("queue_name");
                if (!pending.containsKey(queue)) continue;
                pending.get(queue).set(((Number) row.get("pending")).longValue());
                oldest.get(queue).set(((Number) row.get("oldest_age_seconds")).longValue());
            }
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Queue depth metrics refresh failed error={}", ex.getClass().getSimpleName());
        }
    }
}
