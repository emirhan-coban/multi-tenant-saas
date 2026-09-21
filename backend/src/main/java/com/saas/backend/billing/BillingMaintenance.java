package com.saas.backend.billing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingMaintenance {
    private final BillingService service;
    public BillingMaintenance(BillingService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.billing.maintenance-delay-ms:3600000}",initialDelayString="${app.billing.maintenance-initial-delay-ms:60000}")
    @SchedulerLock(name="billingMaintenance",lockAtMostFor="PT15M")
    public void run() { service.maintain(); }
}
