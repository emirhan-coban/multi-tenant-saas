package com.saas.backend.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Faz 8 operasyon metrikleri. Etiket kardinalitesi bilinçli olarak sınırlıdır: tenant, kullanıcı veya uç nokta
 * gibi yüksek kardinaliteli değerler etiket olarak kullanılmaz (bkz. docs/ops/metrics-and-alerts.md).
 */
@Component
public class OpsMetrics {
    private final MeterRegistry registry;

    public OpsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** İş kuralı veya doğrulama hatası; {@code code} sabit listedir (ApiException kodları). */
    public void apiError(String code, int status) {
        counter("saas.api.errors", "code", code, "status", Integer.toString(status)).increment();
    }

    public void serverError(String method) {
        counter("saas.http.server_errors", "method", method).increment();
    }

    public void bookingDecision(String decision, String source) {
        counter("saas.booking.decisions", "decision", decision, "source", source).increment();
    }

    public void trustTransition(String from, String to, String reason) {
        counter("saas.trust.transitions", "from", from, "to", to, "reason", reason).increment();
    }

    /** kind: tenant_rate_limit, booking_ip_rate_limit, visit_code_failure, blocked_customer, invalid_push_subscription */
    public void abuse(String kind) {
        counter("saas.abuse.events", "kind", kind).increment();
    }

    /** outcome: sent, dropped_gone, failed, retry */
    public void pushDelivery(String outcome) {
        counter("saas.push.deliveries", "outcome", outcome).increment();
    }

    public void featureDisabledHit(String flag) {
        counter("saas.feature.disabled_hits", "flag", flag).increment();
    }

    public void retentionDeleted(String dataset, long rows) {
        if (rows > 0) counter("saas.retention.deleted_rows", "dataset", dataset).increment(rows);
    }

    /**
     * Faz 10 — telefondaki rezervasyon akışının saha ölçümü.
     *
     * <p>Değerler tarayıcıdan gelir ve yalnızca süre dağılımı olarak kaydedilir; kişisel veri
     * taşımaz. Etiketler sabit kümelerdir (görünüm, kalite, aşama), kardinalite düşüktür.</p>
     */
    public void bookingVital(String metric, String view, String quality, long milliseconds) {
        io.micrometer.core.instrument.DistributionSummary.builder("saas.booking.vitals")
                .tags("metric", metric, "view", view, "quality", quality)
                .baseUnit("ms")
                .publishPercentiles(0.5, 0.75, 0.95)
                .register(registry)
                .record(milliseconds);
    }

    /** Akışın hangi aşamasında ölçüm gönderildiği: barber, time, details, review. */
    public void bookingStage(String stage, String view) {
        counter("saas.booking.stage_reached", "stage", stage, "view", view).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
