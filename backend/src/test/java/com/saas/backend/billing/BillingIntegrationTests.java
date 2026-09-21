package com.saas.backend.billing;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class BillingIntegrationTests extends PostgresTestBase {
    @Autowired BillingService billing;
    @MockitoBean BillingProvider provider;
    final JdbcTemplate db=ownerJdbc();
    UUID tenant,user;
    AuthenticatedUser owner;

    @BeforeEach void setup() {
        tenant=UUID.randomUUID(); user=UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,'Europe/Istanbul')",tenant,"Billing Test",tenant.toString());
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)",user,user+"@example.test","unused","Owner");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')",user,tenant);
        owner=new AuthenticatedUser(user,tenant,"OWNER");
        when(provider.plan()).thenReturn(new BillingProvider.Plan("provider-plan",new BigDecimal("499.00"),"TRY",14,"MONTHLY"));
        when(provider.validWebhookSignature(any(),anyString())).thenReturn(true);
    }

    @Test void checkoutIsServerVerifiedAndActivatesTrialWithoutBrowserTrust() {
        when(provider.initialize(anyString(),any())).thenReturn(new BillingProvider.Checkout("secret-token","<div>hosted</div>",900));
        Instant trial=Instant.now().plus(14,ChronoUnit.DAYS), period=trial.plus(31,ChronoUnit.DAYS);
        when(provider.retrieve(eq("secret-token"),anyString())).thenAnswer(call -> new BillingProvider.CheckoutResult(
                call.getArgument(1),"subscription-ref","customer-ref","provider-plan","ACTIVE",trial,period));
        var result=billing.checkout(owner,request());
        assertEquals("<div>hosted</div>",result.htmlContent());
        assertEquals(0,db.queryForObject("select count(*) from tenant_subscriptions where tenant_id=?",Integer.class,tenant));
        assertTrue(billing.completeCheckout("secret-token"));
        assertEquals("TRIALING",billing.status(owner).status());
        assertTrue(billing.status(owner).paidFeaturesEnabled());
        assertTrue(billing.completeCheckout("secret-token"),"callback replay is idempotent");
        verify(provider,times(1)).retrieve(eq("secret-token"),anyString());
    }

    @Test void amountOrCurrencyMismatchStopsCheckoutBeforeProviderForm() {
        when(provider.plan()).thenReturn(new BillingProvider.Plan("provider-plan",new BigDecimal("500.00"),"TRY",14,"MONTHLY"));
        var error=assertThrows(ApiException.class,()->billing.checkout(owner,request()));
        assertEquals("BILLING_PLAN_MISMATCH",error.getCode());
        verify(provider,never()).initialize(anyString(),any());
        assertEquals(0,db.queryForObject("select count(*) from billing_checkout_sessions where tenant_id=?",Integer.class,tenant));
    }

    @Test void signedWebhooksAreIdempotentOrderedAndTenantBound() {
        String reference="sub-"+tenant;
        seedSubscription(reference,"ACTIVE",Instant.now().minus(1,ChronoUnit.DAYS));
        long base=Instant.now().toEpochMilli();
        var failure=event("event-failure","order-failure",reference,"subscription.order.failure",base);
        assertTrue(billing.webhook(failure,"valid"));
        assertTrue(billing.webhook(failure,"valid"));
        assertEquals("PAST_DUE",billing.status(owner).status());
        assertEquals(1,db.queryForObject("select count(*) from billing_events where tenant_id=?",Integer.class,tenant));
        assertEquals(1,db.queryForObject("select count(*) from billing_invoices where tenant_id=?",Integer.class,tenant));

        var stale=event("event-stale","order-stale",reference,"subscription.order.success",base-1000);
        assertTrue(billing.webhook(stale,"valid"));
        assertEquals("PAST_DUE",billing.status(owner).status());
        assertEquals("STALE",db.queryForObject("select outcome from billing_events where provider_event_id='event-stale'",String.class));

        var success=event("event-success","order-success",reference,"subscription.order.success",base+1000);
        assertTrue(billing.webhook(success,"valid"));
        assertEquals("ACTIVE",billing.status(owner).status());
        assertEquals(2,db.queryForObject("select count(*) from billing_invoices where tenant_id=?",Integer.class,tenant));
        assertEquals(2,billing.invoices(owner).size());
        billing.attachInvoiceReference(owner,"order-success","FATURA-2026-001");
        assertEquals("FATURA-2026-001",db.queryForObject("select provider_invoice_ref from billing_invoices where tenant_id=? and provider_order_ref='order-success'",String.class,tenant));

        assertFalse(billing.webhook(event("foreign","foreign-order","another-tenant-ref","subscription.order.success",base+2000),"valid"));
        assertEquals(3,db.queryForObject("select count(*) from billing_events where tenant_id=?",Integer.class,tenant));
    }

    @Test void invalidWebhookSignatureCannotChangeSubscription() {
        String reference="sub-"+tenant;
        seedSubscription(reference,"ACTIVE",null);
        when(provider.validWebhookSignature(any(),anyString())).thenReturn(false);
        var error=assertThrows(ApiException.class,()->billing.webhook(event("forged","order",reference,"subscription.order.failure",Instant.now().toEpochMilli()),"bad"));
        assertEquals("INVALID_WEBHOOK_SIGNATURE",error.getCode());
        assertEquals("ACTIVE",billing.status(owner).status());
    }

    private void seedSubscription(String reference,String status,Instant last) {
        db.update("""
            insert into tenant_subscriptions(tenant_id,plan_code,status,provider_subscription_ref,current_period_ends_at,last_provider_event_at)
            values (?,'PRO_MONTHLY',?,?,now()+interval '31 days',?)
            """,tenant,status,reference,last==null?null:java.sql.Timestamp.from(last));
    }
    private BillingDtos.Webhook event(String id,String order,String reference,String type,long time) {
        return new BillingDtos.Webhook(order,"customer",reference,id,type,time);
    }
    private BillingDtos.CheckoutRequest request() {
        return new BillingDtos.CheckoutRequest("Ada","Lovelace","ada@example.test","+905551112233","10000000146","Test Mah. 1","İstanbul","Türkiye","34000");
    }
}
