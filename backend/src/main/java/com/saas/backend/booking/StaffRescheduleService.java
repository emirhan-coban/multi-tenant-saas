package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.operations.CustomerEmailService;
import com.saas.backend.operations.OperationsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StaffRescheduleService {
    private final EntityManager em;
    private final BookingService bookings;
    private final OperationsService operations;
    private final CustomerEmailService customerEmail;
    private final InAppNotificationService notifications;

    public StaffRescheduleService(EntityManager em, BookingService bookings, OperationsService operations,
                                  CustomerEmailService customerEmail, InAppNotificationService notifications) {
        this.em = em; this.bookings = bookings; this.operations = operations;
        this.customerEmail = customerEmail; this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<BookingDtos.StaffReschedule> list(AuthenticatedUser user, String status) {
        context(user);
        String requested = status == null || status.isBlank() ? "PENDING_APPROVAL" : status;
        if (!List.of("PENDING_APPROVAL", "APPROVED", "REJECTED", "NEEDS_RESELECTION", "CANCELLED").contains(requested))
            throw new ApiException("INVALID_STATUS", "Taşıma talebi durumu geçersiz.");
        return em.createNativeQuery(query() + " where r.tenant_id=:tenant and r.status=:status order by r.created_at,r.id")
                .setParameter("tenant", user.tenantId()).setParameter("status", requested).getResultList().stream()
                .map(value -> map((Object[]) value)).toList();
    }

    @Transactional
    public BookingDtos.StaffReschedule approve(AuthenticatedUser user, UUID id, String key) {
        UUID decisionKey = key(key);
        context(user);
        Object[] row = locked(user, id);
        String status = row[13].toString();
        UUID existingKey = (UUID) row[18];
        if (!"PENDING_APPROVAL".equals(status)) {
            if (decisionKey.equals(existingKey) && "APPROVED".equals(status)) return map(row);
            throw new ApiException("RESCHEDULE_ALREADY_DECIDED", "Taşıma talebi daha önce sonuçlandırılmış.");
        }
        UUID appointmentId = (UUID) row[2];
        var appointment = em.find(Appointment.class, appointmentId, LockModeType.PESSIMISTIC_WRITE);
        if (appointment == null || appointment.getStatus() != Appointment.Status.CONFIRMED)
            return needsReselection(user, id, row, "Randevu artık taşınabilir durumda değil.");
        int requestedVersion = ((Number) row[14]).intValue();
        if (!appointment.getVersion().equals(requestedVersion))
            return needsReselection(user, id, row, "Randevu değişti; müşteri yeni saat seçmeli.");

        Instant requestedStarts = instant(row[12]);
        var available = bookings.rescheduleAvailability(user.tenantId(), appointment.getStaffId(),
                appointment.getServiceId(), appointment.getId(), requestedStarts.atZone(operations.zone(user.tenantId())).toLocalDate());
        if (available.slots().stream().noneMatch(slot -> slot.startsAt().equals(requestedStarts)))
            return needsReselection(user, id, row, "İstenen saat doldu; eski randevu korundu.");

        var target = bookings.validateRescheduleTarget(user.tenantId(), appointment.getStaffId(), appointment.getServiceId(),
                appointment.getId(), requestedStarts);
        Instant previous = appointment.getStartsAt();
        appointment.setStartsAt(requestedStarts); appointment.setEndsAt(target.endsAt()); appointment.setBusyUntil(target.busyUntil());
        var booking = em.find(BookingRequest.class, (UUID) row[1], LockModeType.PESSIMISTIC_WRITE);
        booking.setStartsAt(requestedStarts); booking.setBusyUntil(target.busyUntil()); booking.setUpdatedAt(Instant.now());
        em.flush();
        decide(user, id, "APPROVED", decisionKey, null);
        operations.audit(user, "APPOINTMENT_RESCHEDULED", "appointment", appointmentId,
                Map.of("from", previous.toString(), "to", requestedStarts.toString(), "source", "RESCHEDULE_APPROVAL"));
        customerEmail.queue(user, appointment, "CUSTOMER_RESCHEDULED");
        notifications.notifyCustomer(user.tenantId(), (UUID) row[19], "RESCHEDULE_APPROVED",
                "Taşıma talebin onaylandı", "Yeni randevu saatin kesinleşti.", "booking_request", (UUID) row[1]);
        return find(user, id);
    }

    @Transactional
    public BookingDtos.StaffReschedule reject(AuthenticatedUser user, UUID id, String key, String reason) {
        UUID decisionKey = key(key);
        context(user);
        Object[] row = locked(user, id);
        String status = row[13].toString();
        UUID existingKey = (UUID) row[18];
        if (!"PENDING_APPROVAL".equals(status)) {
            if (decisionKey.equals(existingKey) && "REJECTED".equals(status)) return map(row);
            throw new ApiException("RESCHEDULE_ALREADY_DECIDED", "Taşıma talebi daha önce sonuçlandırılmış.");
        }
        decide(user, id, "REJECTED", decisionKey, blank(reason));
        notifications.notifyCustomer(user.tenantId(), (UUID) row[19], "RESCHEDULE_REJECTED",
                "Taşıma talebin onaylanmadı", "Mevcut randevu saatin değişmeden korunuyor.", "booking_request", (UUID) row[1]);
        operations.audit(user, "RESCHEDULE_REQUEST_REJECTED", "booking_reschedule_request", id,
                reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason.trim()));
        return find(user, id);
    }

    private BookingDtos.StaffReschedule needsReselection(AuthenticatedUser user, UUID id, Object[] row, String reason) {
        decide(user, id, "NEEDS_RESELECTION", null, reason);
        notifications.notifyCustomer(user.tenantId(), (UUID) row[19], "RESCHEDULE_NEEDS_RESELECTION",
                "Yeni bir saat seçmen gerekiyor", reason, "booking_request", (UUID) row[1]);
        return find(user, id);
    }

    private void decide(AuthenticatedUser user, UUID id, String status, UUID key, String reason) {
        em.createNativeQuery("""
                update booking_reschedule_requests set status=:status,decided_by=:actor,decided_at=now(),
                  decision_key=:key,rejection_reason=:reason,updated_at=now(),version=version+1
                where tenant_id=:tenant and id=:id
                """).setParameter("status", status).setParameter("actor", user.userId()).setParameter("key", key)
                .setParameter("reason", reason).setParameter("tenant", user.tenantId()).setParameter("id", id).executeUpdate();
    }

    private Object[] locked(AuthenticatedUser user, UUID id) {
        var ids = em.createNativeQuery("select id from booking_reschedule_requests where tenant_id=:tenant and id=:id for update")
                .setParameter("tenant", user.tenantId()).setParameter("id", id).getResultList();
        if (ids.isEmpty()) throw missing();
        return raw(user, id);
    }
    private BookingDtos.StaffReschedule find(AuthenticatedUser user, UUID id) { return map(raw(user, id)); }
    private Object[] raw(AuthenticatedUser user, UUID id) {
        return (Object[]) em.createNativeQuery(query() + " where r.tenant_id=:tenant and r.id=:id")
                .setParameter("tenant", user.tenantId()).setParameter("id", id).getSingleResult();
    }
    private String query() { return """
            select r.id,r.booking_request_id,r.appointment_id,r.customer_id,c.full_name,c.email,
                   r2.staff_id,p.display_name,r2.service_id,s.name,a.starts_at,r.requested_busy_until,
                   r.requested_starts_at,r.status,r.appointment_version,r.rejection_reason,r.created_at,
                   r.decided_at,r.decision_key,c.id,r.version
            from booking_reschedule_requests r
            join booking_requests r2 on r2.tenant_id=r.tenant_id and r2.id=r.booking_request_id
            join appointments a on a.tenant_id=r.tenant_id and a.id=r.appointment_id
            join tenant_customers tc on tc.tenant_id=r.tenant_id and tc.id=r.customer_id
            join customer_accounts c on c.id=tc.customer_account_id
            join staff p on p.tenant_id=r.tenant_id and p.id=r2.staff_id
            join services s on s.tenant_id=r.tenant_id and s.id=r2.service_id
            """; }
    private BookingDtos.StaffReschedule map(Object[] row) {
        return new BookingDtos.StaffReschedule((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3],
                (String) row[4], (String) row[5], (UUID) row[6], (String) row[7], (UUID) row[8], (String) row[9],
                instant(row[10]), instant(row[12]), row[13].toString(), ((Number) row[14]).intValue(),
                (String) row[15], instant(row[16]), instant(row[17]), ((Number) row[20]).intValue());
    }
    private void context(AuthenticatedUser user) { operations.context(user.tenantId()); }
    private static UUID key(String value) { try { return UUID.fromString(value); } catch (RuntimeException ex) { throw new ApiException("INVALID_KEY", "Idempotency-Key geçerli bir UUID olmalı."); } }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(Object value) { if (value == null) return null; if (value instanceof Instant i) return i; if (value instanceof OffsetDateTime d) return d.toInstant(); return ((Timestamp) value).toInstant(); }
    private static ApiException missing() { return new ApiException("NOT_FOUND", "Taşıma talebi bulunamadı."); }
}
