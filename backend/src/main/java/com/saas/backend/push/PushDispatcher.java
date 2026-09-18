package com.saas.backend.push;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PushDispatcher {
    private final PushDeliveryWorker worker;

    public PushDispatcher(PushDeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.push.interval-ms:15000}", initialDelayString = "${app.push.initial-delay-ms:30000}")
    @SchedulerLock(name = "push-dispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT2S")
    public void dispatch() {
        try {
            // Her parti ayrı transaction: bir partideki uzun ağ beklemesi diğer satırları kilitli tutmaz.
            for (int batch = 0; batch < 20 && worker.processBatch() == PushDeliveryWorker.BATCH_SIZE; batch++) {
                // Kuyruk boşalana ya da 20 parti işlenene kadar devam et.
            }
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).error("Push dispatch failed error={}", ex.getClass().getSimpleName());
        }
    }
}
