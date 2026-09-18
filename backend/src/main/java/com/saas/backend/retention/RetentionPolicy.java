package com.saas.backend.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Saklama süreleri (gün/saat). Denetim kaydı (audit_log) değiştirilemez tutulur ve bu işin kapsamı dışındadır;
 * randevu ve rezervasyon kayıtları raporlama ve güven kanıtı olduğu için silinmez.
 */
@ConfigurationProperties("app.retention")
public record RetentionPolicy(
        Integer idempotencyHours,
        Integer unverifiedVisitCodeDays,
        Integer readNotificationDays,
        Integer notificationMaxDays,
        Integer finishedOutboxDays,
        Integer finishedPushDeliveryDays,
        Integer disabledPushSubscriptionDays,
        Integer finishedAccountEmailDays,
        Integer retentionRunDays,
        Integer batchSize) {

    public RetentionPolicy {
        idempotencyHours = orDefault(idempotencyHours, 48);
        unverifiedVisitCodeDays = orDefault(unverifiedVisitCodeDays, 30);
        readNotificationDays = orDefault(readNotificationDays, 90);
        notificationMaxDays = orDefault(notificationMaxDays, 180);
        finishedOutboxDays = orDefault(finishedOutboxDays, 30);
        finishedPushDeliveryDays = orDefault(finishedPushDeliveryDays, 7);
        disabledPushSubscriptionDays = orDefault(disabledPushSubscriptionDays, 30);
        finishedAccountEmailDays = orDefault(finishedAccountEmailDays, 7);
        retentionRunDays = orDefault(retentionRunDays, 400);
        batchSize = orDefault(batchSize, 5000);
        // İdempotency penceresi, istemcinin makul yeniden deneme süresinden kısa olamaz.
        if (idempotencyHours < 24) throw new IllegalArgumentException("app.retention.idempotency-hours en az 24 olmalı");
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
