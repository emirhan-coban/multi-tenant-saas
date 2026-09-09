package com.saas.backend.operations;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import java.util.UUID;
@Component
public class OutboxScheduler {
    private final JdbcTemplate db;private final OutboxWorker worker;
    public OutboxScheduler(JdbcTemplate db,OutboxWorker worker) {this.db=db;this.worker=worker;}
    @Scheduled(fixedDelayString="${app.outbox.interval-ms:60000}",initialDelayString="${app.outbox.initial-delay-ms:60000}")
    @SchedulerLock(name="appointment-outbox",lockAtMostFor="PT5M",lockAtLeastFor="PT5S")
    public void dispatch() {
        for(UUID tenant:db.queryForList("select id from tenants",UUID.class)) {
            try {worker.process(tenant);}
            catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).error("Outbox transaction failed tenantId={}",tenant);}
        }
    }
}
