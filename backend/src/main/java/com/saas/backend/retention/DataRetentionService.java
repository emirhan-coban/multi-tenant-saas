package com.saas.backend.retention;

import com.saas.backend.ops.OpsMetrics;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@EnableConfigurationProperties(RetentionPolicy.class)
public class DataRetentionService {
    private final JdbcTemplate db;
    private final TenantRetentionWorker tenants;
    private final RetentionPolicy policy;
    private final OpsMetrics metrics;
    private final ObjectMapper json;

    public DataRetentionService(JdbcTemplate db, TenantRetentionWorker tenants, RetentionPolicy policy, OpsMetrics metrics, ObjectMapper json) {
        this.db = db;
        this.tenants = tenants;
        this.policy = policy;
        this.metrics = metrics;
        this.json = json;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}", zone = "Europe/Istanbul")
    @SchedulerLock(name = "data-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).error("Data retention run failed error={}", ex.getClass().getSimpleName());
        }
    }

    /** Tüm tenant'lar ve global tablolar için bir saklama turu çalıştırır; silinen satır sayılarını döndürür. */
    public Map<String, Long> run() {
        Instant started = Instant.now();
        var totals = new LinkedHashMap<String, Long>();
        int tenantCount = 0;
        for (UUID tenant : db.queryForList("select ops_tenant_ids()", UUID.class)) {
            try {
                tenants.purge(tenant, policy).forEach((dataset, rows) -> totals.merge(dataset, rows, Long::sum));
                tenantCount++;
            } catch (RuntimeException ex) {
                LoggerFactory.getLogger(getClass()).error("Tenant retention failed tenantId={} error={}", tenant, ex.getClass().getSimpleName());
            }
        }
        totals.put("push_deliveries", (long) db.update("""
                delete from push_deliveries where status <> 'PENDING' and finished_at < now() - (? * interval '1 day')
                """, policy.finishedPushDeliveryDays()));
        totals.put("push_subscriptions", (long) db.update("""
                delete from push_subscriptions where disabled_at < now() - (? * interval '1 day')
                """, policy.disabledPushSubscriptionDays()));
        totals.put("account_email_outbox", (long) db.update("""
                delete from account_email_outbox where status in ('SENT','SKIPPED','FAILED')
                  and finished_at < now() - (? * interval '1 day')
                """, policy.finishedAccountEmailDays()));
        // Faz 9: kullanılmış/süresi dolmuş hesap token'ları saklanmaz.
        totals.put("account_tokens", (long) db.update("""
                delete from account_tokens
                where (used_at is null and expires_at < now() - interval '1 day')
                   or (used_at is not null and used_at < now() - (? * interval '1 day'))
                """, policy.retentionRunDays()));
        totals.put("data_retention_runs", (long) db.update("""
                delete from data_retention_runs where finished_at < now() - (? * interval '1 day')
                """, policy.retentionRunDays()));
        totals.forEach(metrics::retentionDeleted);
        db.update("insert into data_retention_runs(started_at,deleted,tenant_count) values (?,cast(? as jsonb),?)",
                Timestamp.from(started), json.writeValueAsString(totals), tenantCount);
        LoggerFactory.getLogger(getClass()).info("Data retention completed tenants={} deleted={}", tenantCount, totals);
        return totals;
    }
}
