package com.saas.backend.operations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import java.util.UUID;
@Service
public class OutboxWorker {
    private final JdbcTemplate db; private final OperationsService operations; private final NotificationSender sender;
    private final CustomerEmailService customerEmail;
    public OutboxWorker(JdbcTemplate db,OperationsService operations,NotificationSender sender,CustomerEmailService customerEmail) {this.db=db;this.operations=operations;this.sender=sender;this.customerEmail=customerEmail;}
    @Transactional public void process(UUID tenant) {
        operations.context(tenant);
        MDC.put("tenantId",tenant.toString());MDC.put("traceId",UUID.randomUUID().toString());
        try {
            var rows=db.queryForList("select * from outbox where tenant_id=? and status='PENDING' and available_at<=now() order by available_at,id limit 10 for update skip locked",tenant);
            for(var row:rows) {
                UUID id=(UUID)row.get("id"),recipient=(UUID)row.get("recipient_id"),appointment=(UUID)row.get("appointment_id");
                if(row.get("event_type").toString().startsWith("CUSTOMER_")) {
                    boolean customerActive = Boolean.TRUE.equals(db.queryForObject("""
                        select exists(
                          select 1 from appointments a
                          join tenant_customers tc on tc.tenant_id=a.tenant_id and tc.id=a.customer_id
                          join customer_accounts ca on ca.id=tc.customer_account_id
                          where a.tenant_id=? and a.id=? and ca.active and ca.anonymized_at is null)
                        """, Boolean.class, tenant, appointment));
                    if (!customerActive) {
                        db.update("update outbox set status='SKIPPED' where id=? and tenant_id=?", id, tenant);
                        continue;
                    }
                    try {
                        switch(customerEmail.deliver(row)) {
                            case SENT -> db.update("update outbox set status='SENT',sent_at=now(),attempts=attempts+1 where id=? and tenant_id=?",id,tenant);
                            case SKIPPED -> db.update("update outbox set status='SKIPPED' where id=? and tenant_id=?",id,tenant);
                            case DEFERRED -> db.update("update outbox set available_at=now()+interval '1 minute' where id=? and tenant_id=?",id,tenant);
                        }
                    } catch(RuntimeException ex) {
                        int attempt=((Number)row.get("attempts")).intValue()+1;
                        db.update("update outbox set attempts=?,status=?,available_at=now()+(? * interval '1 minute') where id=? and tenant_id=?",
                            attempt,attempt>=5?"FAILED":"PENDING",Math.min(60,1<<attempt),id,tenant);
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Customer email delivery failed eventId={} attempt={}",id,attempt);
                    }
                    continue;
                }
                boolean valid=Boolean.TRUE.equals(db.queryForObject("""
                    select exists(select 1 from appointments a where a.tenant_id=? and a.id=? and a.status='CONFIRMED'
                    and a.starts_at>now() and a.starts_at=(select (payload->>'startsAt')::timestamptz from outbox where id=? and tenant_id=?))
                    and coalesce((select reminders_enabled from notification_preferences where tenant_id=? and user_id=?),true)
                    """,Boolean.class,tenant,appointment,id,tenant,tenant,recipient));
                if(!valid) {db.update("update outbox set status='SKIPPED' where id=? and tenant_id=?",id,tenant);continue;}
                try {
                    sender.send(id,tenant,recipient,appointment);
                } catch(RuntimeException e) {
                    int attempt=((Number)row.get("attempts")).intValue()+1;
                    db.update("update outbox set attempts=?,status=?,available_at=now()+(? * interval '1 minute') where id=? and tenant_id=?",
                        attempt,attempt>=5?"FAILED":"PENDING",Math.min(60,1<<attempt),id,tenant);
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reminder delivery failed eventId={} attempt={}",id,attempt);
                    continue;
                }
                db.update("update outbox set status='SENT',sent_at=now(),attempts=attempts+1 where id=? and tenant_id=?",id,tenant);
            }
        } finally {MDC.remove("tenantId");MDC.remove("traceId");}
    }
}
