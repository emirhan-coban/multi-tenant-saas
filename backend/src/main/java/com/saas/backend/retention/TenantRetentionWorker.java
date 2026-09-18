package com.saas.backend.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** RLS zorunlu tablolarda silme yalnızca ilgili tenant bağlamında, tenant başına ayrı transaction ile yapılır. */
@Service
public class TenantRetentionWorker {
    private final JdbcTemplate db;

    public TenantRetentionWorker(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Long> purge(UUID tenant, RetentionPolicy policy) {
        db.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenant.toString());
        var deleted = new LinkedHashMap<String, Long>();
        deleted.put("idempotency_keys", batched(policy, """
                delete from idempotency_keys where ctid in (
                  select ctid from idempotency_keys where tenant_id=? and created_at < now() - (? * interval '1 hour') limit ?)
                """, tenant, policy.idempotencyHours()));
        deleted.put("visit_verifications", batched(policy, """
                delete from visit_verifications where ctid in (
                  select ctid from visit_verifications where tenant_id=? and verified_at is null
                    and expires_at < now() - (? * interval '1 day') limit ?)
                """, tenant, policy.unverifiedVisitCodeDays()));
        deleted.put("in_app_notifications", batched(policy, """
                delete from in_app_notifications where ctid in (
                  select ctid from in_app_notifications where tenant_id=?
                    and ((read_at is not null and created_at < now() - (? * interval '1 day'))
                         or created_at < now() - (? * interval '1 day')) limit ?)
                """, tenant, policy.readNotificationDays(), policy.notificationMaxDays()));
        deleted.put("outbox", batched(policy, """
                delete from outbox where ctid in (
                  select ctid from outbox where tenant_id=? and status <> 'PENDING'
                    and created_at < now() - (? * interval '1 day') limit ?)
                """, tenant, policy.finishedOutboxDays()));
        return deleted;
    }

    private long batched(RetentionPolicy policy, String sql, Object... args) {
        Object[] withLimit = new Object[args.length + 1];
        System.arraycopy(args, 0, withLimit, 0, args.length);
        withLimit[args.length] = policy.batchSize();
        long total = 0;
        for (int round = 0; round < 20; round++) {
            int changed = db.update(sql, withLimit);
            total += changed;
            if (changed < policy.batchSize()) break;
        }
        return total;
    }
}
