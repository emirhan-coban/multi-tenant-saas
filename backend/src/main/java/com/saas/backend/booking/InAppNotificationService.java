package com.saas.backend.booking;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class InAppNotificationService {
    private final EntityManager em;

    public InAppNotificationService(EntityManager em) { this.em = em; }

    @Transactional
    public void notifyManagers(UUID tenantId, String eventType, String title, String body,
                               String relatedType, UUID relatedId) {
        context(tenantId);
        em.createNativeQuery("""
                insert into in_app_notifications(tenant_id,recipient_type,recipient_id,event_type,title,body,related_type,related_id)
                select :tenant,'STAFF',m.user_id,:event,:title,:body,:relatedType,:relatedId
                from memberships m where m.tenant_id=:tenant and m.role in ('OWNER','MANAGER')
                """).setParameter("tenant", tenantId).setParameter("event", eventType)
                .setParameter("title", title).setParameter("body", body)
                .setParameter("relatedType", relatedType).setParameter("relatedId", relatedId).executeUpdate();
    }

    @Transactional
    public void notifyCustomer(UUID tenantId, UUID accountId, String eventType, String title, String body,
                               String relatedType, UUID relatedId) {
        context(tenantId);
        em.createNativeQuery("""
                insert into in_app_notifications(tenant_id,recipient_type,recipient_id,event_type,title,body,related_type,related_id)
                values (:tenant,'CUSTOMER',:recipient,:event,:title,:body,:relatedType,:relatedId)
                """).setParameter("tenant", tenantId).setParameter("recipient", accountId)
                .setParameter("event", eventType).setParameter("title", title).setParameter("body", body)
                .setParameter("relatedType", relatedType).setParameter("relatedId", relatedId).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Notification> staff(AuthenticatedUser user) {
        context(user.tenantId());
        return rows("STAFF", user.userId());
    }

    @Transactional
    public Notification readStaff(AuthenticatedUser user, UUID id) {
        context(user.tenantId());
        return markRead(user.tenantId(), "STAFF", user.userId(), id);
    }

    @Transactional(readOnly = true)
    public List<Notification> customer(UUID accountId) {
        return notificationTenants(accountId).stream().flatMap(tenant -> {
                    context(tenant);
                    return rows("CUSTOMER", accountId).stream();
                }).sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).limit(50).toList();
    }

    @Transactional
    public Notification readCustomer(UUID accountId, UUID id) {
        var tenants = notificationOwner(id, accountId);
        if (tenants.isEmpty()) throw missing();
        context(tenants.getFirst());
        return markRead(tenants.getFirst(), "CUSTOMER", accountId, id);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> notificationTenants(UUID accountId) {
        return (List<UUID>) (List<?>) em.createNativeQuery("select tenant_id from resolve_customer_booking_tenants(:recipient)")
                .setParameter("recipient", accountId).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> notificationOwner(UUID id, UUID accountId) {
        return (List<UUID>) (List<?>) em.createNativeQuery("select tenant_id from resolve_customer_notification(:id,:account)")
                .setParameter("id", id).setParameter("account", accountId).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Notification> rows(String type, UUID recipient) {
        return em.createNativeQuery("""
                select id,tenant_id,event_type,title,body,related_type,related_id,read_at,created_at
                from in_app_notifications where recipient_type=:type and recipient_id=:recipient
                order by created_at desc,id desc limit 50
                """).setParameter("type", type).setParameter("recipient", recipient).getResultList().stream()
                .map(value -> map((Object[]) value)).toList();
    }

    private Notification markRead(UUID tenantId, String type, UUID recipient, UUID id) {
        int changed = em.createNativeQuery("""
                update in_app_notifications set read_at=coalesce(read_at,now())
                where tenant_id=:tenant and id=:id and recipient_type=:type and recipient_id=:recipient
                """).setParameter("tenant", tenantId).setParameter("id", id).setParameter("type", type)
                .setParameter("recipient", recipient).executeUpdate();
        if (changed != 1) throw missing();
        Object[] row = (Object[]) em.createNativeQuery("""
                select id,tenant_id,event_type,title,body,related_type,related_id,read_at,created_at
                from in_app_notifications where id=:id
                """).setParameter("id", id).getSingleResult();
        return map(row);
    }

    private Notification map(Object[] row) {
        return new Notification((UUID) row[0], (UUID) row[1], row[2].toString(), row[3].toString(),
                row[4].toString(), (String) row[5], (UUID) row[6], instant(row[7]), instant(row[8]));
    }

    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant result) return result;
        if (value instanceof OffsetDateTime result) return result.toInstant();
        return ((Timestamp) value).toInstant();
    }

    private static ApiException missing() { return new ApiException("NOT_FOUND", "Bildirim bulunamadı."); }
    public record Notification(UUID id, UUID tenantId, String eventType, String title, String body,
                               String relatedType, UUID relatedId, Instant readAt, Instant createdAt) {}
}
