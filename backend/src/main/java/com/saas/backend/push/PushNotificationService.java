package com.saas.backend.push;

import com.saas.backend.flags.FeatureFlag;
import com.saas.backend.flags.FeatureFlagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Uygulama içi bildirim oluşturulduğu aynı transaction'da push teslimatlarını kuyruğa alır.
 * Transaction geri alınırsa push da gönderilmez; gönderim {@link PushDispatcher} tarafından yapılır.
 * Bildirim içeriği kısa tutulur: kilit ekranında görünebileceği için telefon, e-posta, not gibi kişisel veri eklenmez.
 */
@Service
public class PushNotificationService {
    private final JdbcTemplate db;
    private final FeatureFlagService flags;
    private final WebPushConfiguration.WebPushKeys keys;

    public PushNotificationService(JdbcTemplate db, FeatureFlagService flags, WebPushConfiguration.WebPushKeys keys) {
        this.db = db;
        this.flags = flags;
        this.keys = keys;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int queueForManagers(UUID tenantId, String eventType, String title, String body) {
        if (!active(tenantId)) return 0;
        return db.update("""
                insert into push_deliveries(subscription_id,tenant_id,event_type,title,body,url)
                select p.id,?,?,?,?,? from memberships m
                join push_subscriptions p on p.owner_type='STAFF' and p.owner_id=m.user_id and p.disabled_at is null
                where m.tenant_id=? and m.role in ('OWNER','MANAGER')
                """, tenantId, eventType, clip(title, 80), clip(body, 200), staffUrl(eventType), tenantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int queueForCustomer(UUID tenantId, UUID accountId, String eventType, String title, String body) {
        if (!active(tenantId)) return 0;
        return db.update("""
                insert into push_deliveries(subscription_id,tenant_id,event_type,title,body,url)
                select p.id,?,?,?,?,'/customer/account' from push_subscriptions p
                where p.owner_type='CUSTOMER' and p.owner_id=? and p.disabled_at is null
                """, tenantId, eventType, clip(title, 80), clip(body, 200), accountId);
    }

    @Transactional
    public int queueTest(PushSubscriptionService.OwnerType ownerType, UUID ownerId, UUID tenantId) {
        if (!keys.configured()) return 0;
        return db.update("""
                insert into push_deliveries(subscription_id,tenant_id,event_type,title,body,url)
                select p.id,?,'PUSH_TEST','Bildirimler açık','Bu cihaz randevu bildirimlerini alacak.',?
                from push_subscriptions p where p.owner_type=? and p.owner_id=? and p.disabled_at is null
                  and not exists (select 1 from push_deliveries d where d.subscription_id=p.id and d.event_type='PUSH_TEST'
                                  and d.created_at>now()-interval '1 minute')
                """, tenantId, ownerType == PushSubscriptionService.OwnerType.STAFF ? "/app/notifications" : "/customer/account",
                ownerType.name(), ownerId);
    }

    private boolean active(UUID tenantId) {
        return keys.configured() && flags.enabled(FeatureFlag.PUSH_NOTIFICATIONS, tenantId);
    }

    static String staffUrl(String eventType) {
        if (eventType.startsWith("BOOKING_REQUEST")) return "/app/booking-requests";
        if (eventType.startsWith("RESCHEDULE")) return "/app/booking-requests";
        if (eventType.startsWith("APPOINTMENT")) return "/app/appointments";
        return "/app/notifications";
    }

    static String clip(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
