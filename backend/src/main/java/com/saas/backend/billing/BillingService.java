package com.saas.backend.billing;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@Service
public class BillingService {
    private static final String PLAN="PRO_MONTHLY";
    private final JdbcTemplate db;
    private final BillingProvider provider;
    private final ObjectMapper json;

    public BillingService(JdbcTemplate db, BillingProvider provider, ObjectMapper json) {
        this.db=db; this.provider=provider; this.json=json;
    }

    @Transactional(readOnly=true)
    public BillingDtos.SubscriptionStatus status(AuthenticatedUser user) {
        context(user.tenantId());
        var plan=db.queryForMap("select * from subscription_plans where code=? and active",PLAN);
        var rows=db.queryForList("select * from tenant_subscriptions where tenant_id=?",user.tenantId());
        if(rows.isEmpty()) return response(plan,"FREE",false,null,null,null,false);
        var sub=rows.getFirst(); String status=sub.get("status").toString();
        Instant grace=instant(sub.get("grace_ends_at"));
        boolean enabled=switch(status) {
            case "TRIALING","ACTIVE","CANCEL_AT_PERIOD_END" -> true;
            case "PAST_DUE" -> grace!=null && grace.isAfter(Instant.now());
            default -> false;
        };
        if("PAST_DUE".equals(status) && !enabled) status="RESTRICTED";
        return response(plan,status,enabled,instant(sub.get("trial_ends_at")),instant(sub.get("current_period_ends_at")),grace,
                "CANCEL_AT_PERIOD_END".equals(status));
    }

    @Transactional
    public BillingDtos.CheckoutResponse checkout(AuthenticatedUser user, BillingDtos.CheckoutRequest request) {
        context(user.tenantId()); verifyProviderPlan();
        var plan=db.queryForMap("select amount_minor,currency from subscription_plans where code=? and active",PLAN);
        UUID id=UUID.randomUUID(); String conversation="tenant-"+user.tenantId()+"-"+id;
        Instant expires=Instant.now().plus(30,ChronoUnit.MINUTES);
        db.update("""
            insert into billing_checkout_sessions(id,tenant_id,plan_code,conversation_id,expected_amount_minor,expected_currency,status,expires_at)
            values (?,?,?,?,?,?,'CREATED',?)
            """,id,user.tenantId(),PLAN,conversation,plan.get("amount_minor"),plan.get("currency"),Timestamp.from(expires));
        var checkout=provider.initialize(conversation,new BillingProvider.Customer(request.name(),request.surname(),request.email(),
                request.gsmNumber(),request.identityNumber(),request.address(),request.city(),request.country(),request.zipCode()));
        expires=Instant.now().plusSeconds(Math.max(60,Math.min(checkout.expiresInSeconds(),3600)));
        db.update("update billing_checkout_sessions set provider_token_hash=?,status='INITIALIZED',expires_at=? where id=?",
                hash(checkout.token()),Timestamp.from(expires),id);
        return new BillingDtos.CheckoutResponse(id.toString(),checkout.htmlContent(),expires);
    }

    @Transactional
    public boolean completeCheckout(String token) {
        if(token==null || token.isBlank()) return false;
        UUID tenant=db.queryForObject("select billing_tenant_for_checkout_token(?)",UUID.class,hash(token));
        if(tenant==null) return false;
        context(tenant);
        var rows=db.queryForList("select * from billing_checkout_sessions where tenant_id=? and provider_token_hash=? for update",tenant,hash(token));
        if(rows.isEmpty()) return false;
        var session=rows.getFirst();
        if("COMPLETED".equals(session.get("status"))) return true;
        var result=provider.retrieve(token,session.get("conversation_id").toString());
        if(!session.get("conversation_id").equals(result.conversationId())) throw mismatch();
        var verifiedPlan=verifyProviderPlan();
        if(!verifiedPlan.reference().equals(result.pricingPlanReference()) || !"ACTIVE".equals(result.status())) throw mismatch();
        Instant now=Instant.now(); Instant trial=result.trialEndsAt();
        String status=trial!=null && trial.isAfter(now)?"TRIALING":"ACTIVE";
        Instant period=result.periodEndsAt()!=null?result.periodEndsAt():now.plus(31,ChronoUnit.DAYS);
        db.update("""
            insert into tenant_subscriptions(tenant_id,plan_code,status,provider_subscription_ref,provider_customer_ref,trial_ends_at,current_period_ends_at)
            values (?,?,?,?,?,?,?)
            on conflict(tenant_id) do update set plan_code=excluded.plan_code,status=excluded.status,
              provider_subscription_ref=excluded.provider_subscription_ref,provider_customer_ref=excluded.provider_customer_ref,
              trial_ends_at=excluded.trial_ends_at,current_period_ends_at=excluded.current_period_ends_at,
              grace_ends_at=null,cancel_requested_at=null,updated_at=now()
            """,tenant,PLAN,status,result.subscriptionReference(),result.customerReference(),timestamp(trial),timestamp(period));
        db.update("update billing_checkout_sessions set status='COMPLETED',completed_at=now() where id=?",session.get("id"));
        return true;
    }

    @Transactional
    public boolean webhook(BillingDtos.Webhook event,String signature) {
        if(!provider.validWebhookSignature(event,signature)) throw new ApiException("INVALID_WEBHOOK_SIGNATURE","Geçersiz webhook imzası.");
        if(!event.iyziEventType().equals("subscription.order.success") && !event.iyziEventType().equals("subscription.order.failure"))
            throw new ApiException("INVALID_WEBHOOK_EVENT","Desteklenmeyen webhook olayı.");
        UUID tenant=db.queryForObject("select billing_tenant_for_subscription(?)",UUID.class,event.subscriptionReferenceCode());
        if(tenant==null) return false;
        context(tenant);
        String payloadHash=hash(json.writeValueAsString(event));
        int inserted=db.update("""
            insert into billing_events(tenant_id,provider_event_id,provider_subscription_ref,provider_order_ref,event_type,event_at,payload_hash,outcome)
            values (?,?,?,?,?,?,?,'APPLIED') on conflict(provider_event_id) do nothing
            """,tenant,event.iyziReferenceCode(),event.subscriptionReferenceCode(),event.orderReferenceCode(),event.iyziEventType(),
                Timestamp.from(Instant.ofEpochMilli(event.iyziEventTime())),payloadHash);
        if(inserted==0) return true;
        var sub=db.queryForMap("select * from tenant_subscriptions where tenant_id=? for update",tenant);
        Instant eventAt=Instant.ofEpochMilli(event.iyziEventTime()), last=instant(sub.get("last_provider_event_at"));
        if(last!=null && !eventAt.isAfter(last)) {
            db.update("update billing_events set outcome='STALE' where provider_event_id=?",event.iyziReferenceCode());
            return true;
        }
        var plan=db.queryForMap("select amount_minor,currency,grace_days from subscription_plans where code=?",sub.get("plan_code"));
        if(event.iyziEventType().endsWith("success")) {
            db.update("update tenant_subscriptions set status='ACTIVE',grace_ends_at=null,last_provider_event_at=?,current_period_ends_at=?,updated_at=now() where tenant_id=?",
                    Timestamp.from(eventAt),Timestamp.from(eventAt.plus(31,ChronoUnit.DAYS)),tenant);
            db.update("""
                insert into billing_invoices(tenant_id,provider_order_ref,amount_minor,currency,status,occurred_at)
                values (?,?,?,?, 'PAID',?) on conflict(provider_order_ref) do nothing
                """,tenant,event.orderReferenceCode(),plan.get("amount_minor"),plan.get("currency"),Timestamp.from(eventAt));
        } else {
            int grace=((Number)plan.get("grace_days")).intValue();
            db.update("update tenant_subscriptions set status='PAST_DUE',grace_ends_at=?,last_provider_event_at=?,updated_at=now() where tenant_id=?",
                    Timestamp.from(eventAt.plus(grace,ChronoUnit.DAYS)),Timestamp.from(eventAt),tenant);
            db.update("""
                insert into billing_invoices(tenant_id,provider_order_ref,amount_minor,currency,status,occurred_at)
                values (?,?,?,?, 'FAILED',?) on conflict(provider_order_ref) do nothing
                """,tenant,event.orderReferenceCode(),plan.get("amount_minor"),plan.get("currency"),Timestamp.from(eventAt));
        }
        return true;
    }

    @Transactional
    public void cancelAtPeriodEnd(AuthenticatedUser user) {
        context(user.tenantId());
        int changed=db.update("""
            update tenant_subscriptions set status='CANCEL_AT_PERIOD_END',cancel_requested_at=now(),updated_at=now()
            where tenant_id=? and status in ('TRIALING','ACTIVE','PAST_DUE')
            """,user.tenantId());
        if(changed==0) throw new ApiException("SUBSCRIPTION_NOT_CANCELLABLE","İptal edilebilecek etkin abonelik bulunamadı.");
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> invoices(AuthenticatedUser user) {
        context(user.tenantId());
        return db.queryForList("""
            select provider_order_ref as "orderReference",provider_invoice_ref as "invoiceReference",
                   amount_minor as "amountMinor",currency,status,occurred_at as "occurredAt"
            from billing_invoices where tenant_id=? order by occurred_at desc limit 100
            """,user.tenantId());
    }

    @Transactional
    public void attachInvoiceReference(AuthenticatedUser user,String orderReference,String invoiceReference) {
        context(user.tenantId());
        int changed=db.update("update billing_invoices set provider_invoice_ref=? where tenant_id=? and provider_order_ref=?",
                invoiceReference.trim(),user.tenantId(),orderReference);
        if(changed==0) throw new ApiException("NOT_FOUND","Tahsilat kaydı bulunamadı.");
    }

    @Transactional
    public void maintain() {
        db.queryForObject("select billing_restrict_expired()",Integer.class);
        var due=db.queryForList("select * from billing_due_cancellations()");
        for(var row:due) {
            UUID tenant=(UUID)row.get("tenant_id"); context(tenant);
            provider.cancel(row.get("provider_subscription_ref").toString());
            db.update("update tenant_subscriptions set status='CANCELLED',updated_at=now() where tenant_id=?",tenant);
        }
    }

    private BillingProvider.Plan verifyProviderPlan() {
        var local=db.queryForMap("select * from subscription_plans where code=? and active",PLAN); var remote=provider.plan();
        int amount=remote.price().movePointRight(2).intValueExact();
        if(amount!=((Number)local.get("amount_minor")).intValue() || !remote.currency().equals(local.get("currency"))
                || !"MONTHLY".equals(remote.interval()) || remote.trialDays()!=((Number)local.get("trial_days")).intValue()) throw mismatch();
        return remote;
    }
    private static ApiException mismatch() { return new ApiException("BILLING_PLAN_MISMATCH","Ödeme planı doğrulanamadı; tahsilat başlatılmadı."); }
    private void context(UUID tenant) { db.queryForObject("select set_config('app.current_tenant',?,true)",String.class,tenant.toString()); }
    private BillingDtos.SubscriptionStatus response(Map<String,Object> plan,String status,boolean enabled,Instant trial,Instant period,Instant grace,boolean cancel) {
        return new BillingDtos.SubscriptionStatus(PLAN,plan.get("name").toString(),((Number)plan.get("amount_minor")).intValue(),plan.get("currency").toString(),status,enabled,trial,period,grace,cancel);
    }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant instant(Object value) { return value instanceof Timestamp t?t.toInstant():null; }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); } }
}
