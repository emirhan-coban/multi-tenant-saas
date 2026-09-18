package com.saas.backend.account;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AccountEmailDispatcher {
    private final JdbcTemplate db;
    private final AccountEmailWorker worker;

    public AccountEmailDispatcher(JdbcTemplate db, AccountEmailWorker worker) {
        this.db = db;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.account.mail-interval-ms:15000}",
            initialDelayString = "${app.account.mail-initial-delay-ms:30000}")
    @SchedulerLock(name = "account-email-outbox", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void dispatch() {
        try {
            worker.recoverUncertainDeliveries();
            for (UUID id : db.queryForList("""
                    select id from account_email_outbox
                    where status='PENDING' and available_at<=now() order by available_at,id limit 20
                    """, UUID.class)) {
                worker.deliver(id);
            }
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).error("Account email dispatch failed error={}", ex.getClass().getSimpleName());
        }
    }
}
