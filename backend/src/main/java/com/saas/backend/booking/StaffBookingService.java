package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.appointment.AppointmentRepository;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.customer.CustomerAccount;
import com.saas.backend.hizmet.Hizmet;
import com.saas.backend.personel.Personel;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class StaffBookingService {
    private final EntityManager em;
    private final AppointmentRepository appointments;
    private final com.saas.backend.operations.OperationsService operations;
    private final com.saas.backend.operations.CustomerEmailService customerEmail;
    private final BookingHoldExpiryService holdExpiry;
    private final BookingAppointmentFactory appointmentFactory;
    private final InAppNotificationService notifications;
    private final com.saas.backend.ops.OpsMetrics metrics;

    public StaffBookingService(EntityManager em, AppointmentRepository appointments,
                               com.saas.backend.operations.OperationsService operations,
                               com.saas.backend.operations.CustomerEmailService customerEmail,
                               BookingHoldExpiryService holdExpiry,
                               BookingAppointmentFactory appointmentFactory,
                               InAppNotificationService notifications,
                               com.saas.backend.ops.OpsMetrics metrics) {
        this.em = em;
        this.appointments = appointments;
        this.operations = operations;
        this.customerEmail = customerEmail;
        this.holdExpiry = holdExpiry;
        this.appointmentFactory = appointmentFactory;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    @Transactional
    public List<BookingDtos.StaffResponse> list(AuthenticatedUser user, BookingRequestStatus status) {
        context(user.tenantId());
        holdExpiry.expire(user.tenantId());
        return rows(user.tenantId(), status).stream().map(this::response).toList();
    }

    @Transactional
    public BookingDtos.StaffResponse approve(AuthenticatedUser user, UUID requestId, String key) {
        UUID decisionKey = validKey(key);
        context(user.tenantId());
        holdExpiry.expire(user.tenantId());
        var booking = locked(user.tenantId(), requestId);
        var replay = replay(booking, decisionKey, "APPROVE");
        if (replay != null) return response(row(user.tenantId(), requestId));
        requirePending(booking);

        ZoneId zone = operations.zone(user.tenantId());
        LocalDate date = booking.getStartsAt().atZone(zone).toLocalDate();
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", BookingService.slotLock(booking.getStaffId(), date)).getSingleResult();
        if (appointmentConflict(booking))
            throw new ApiException("SLOT_UNAVAILABLE", "Bu saat artık başka bir randevuyla çakışıyor.");

        var service = service(user.tenantId(), booking.getServiceId());
        person(user.tenantId(), booking.getStaffId());
        var tenantCustomer = em.find(TenantCustomer.class, booking.getCustomerId());
        if (tenantCustomer == null) throw missing("Müşteri profili");
        if (tenantCustomer.getTrustState() == CustomerTrustState.BLOCKED)
            throw new ApiException("CUSTOMER_BLOCKED", "Engellenen müşterinin rezervasyon talebi onaylanamaz.");
        var customer = customerAccount(tenantCustomer.getCustomerAccountId());
        operations.checkAppointmentQuota(user.tenantId());

        var appointment = appointmentFactory.create(user.tenantId(), tenantCustomer.getId(), customer, service, booking);
        appointments.saveAndFlush(appointment);
        decide(booking, user.userId(), decisionKey, "APPROVE");
        booking.setStatus(BookingRequestStatus.APPROVED);
        booking.setAppointmentId(appointment.getId());
        em.flush();

        operations.appointmentCreated(user, appointment);
        operations.audit(user, "BOOKING_REQUEST_APPROVED", "booking_request", booking.getId(),
                Map.of("appointmentId", appointment.getId(), "startsAt", booking.getStartsAt().toString()));
        customerEmail.queue(user, appointment, "CUSTOMER_CONFIRMATION");
        notifications.notifyCustomer(user.tenantId(), tenantCustomer.getCustomerAccountId(), "BOOKING_REQUEST_APPROVED",
                "Randevun onaylandı", service.getName() + " · " + BookingService.notificationTime(booking.getStartsAt(), zone),
                "booking_request", booking.getId());
        metrics.bookingApprovalLatency("approved", Duration.between(booking.getCreatedAt(),Instant.now()).toSeconds());
        return response(row(user.tenantId(), requestId));
    }

    @Transactional
    public BookingDtos.StaffResponse reject(AuthenticatedUser user, UUID requestId, String key, String reason) {
        UUID decisionKey = validKey(key);
        context(user.tenantId());
        holdExpiry.expire(user.tenantId());
        var booking = locked(user.tenantId(), requestId);
        var replay = replay(booking, decisionKey, "REJECT");
        if (replay != null) return response(row(user.tenantId(), requestId));
        requirePending(booking);
        decide(booking, user.userId(), decisionKey, "REJECT");
        booking.setStatus(BookingRequestStatus.REJECTED);
        booking.setRejectionReason(blankToNull(reason));
        em.flush();
        operations.audit(user, "BOOKING_REQUEST_REJECTED", "booking_request", booking.getId(),
                booking.getRejectionReason() == null ? Map.of() : Map.of("reason", booking.getRejectionReason()));
        var rejectedCustomer = em.find(TenantCustomer.class, booking.getCustomerId());
        if (rejectedCustomer != null)
            notifications.notifyCustomer(user.tenantId(), rejectedCustomer.getCustomerAccountId(), "BOOKING_REQUEST_REJECTED",
                    "Talebin onaylanmadı", "Başka bir saat seçerek yeniden talep oluşturabilirsin.", "booking_request", booking.getId());
        metrics.bookingApprovalLatency("rejected", Duration.between(booking.getCreatedAt(),Instant.now()).toSeconds());
        return response(row(user.tenantId(), requestId));
    }

    @Transactional
    public BookingDtos.TrustResponse updateTrust(AuthenticatedUser user, UUID customerId, BookingDtos.TrustUpdate request) {
        context(user.tenantId());
        if (request.trustState() == CustomerTrustState.NEW)
            throw new ApiException("INVALID_TRUST_STATE", "Müşteri manuel olarak NEW durumuna alınamaz.");
        String reason = blankToNull(request.reason());
        if (reason == null) throw new ApiException("TRUST_REASON_REQUIRED", "Güven değişikliği gerekçesi zorunludur.");
        var customer = em.createQuery("select c from TenantCustomer c where c.id=:id and c.tenantId=:tenant", TenantCustomer.class)
                .setParameter("id", customerId).setParameter("tenant", user.tenantId())
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(() -> missing("Müşteri profili"));
        if (!Objects.equals(customer.getVersion(), request.expectedVersion()))
            throw new ApiException("STALE_VERSION", "Müşteri güven durumu başka biri tarafından değiştirildi. Listeyi yenileyin.");
        var before = customer.getTrustState();
        if (before == request.trustState()) throw new ApiException("INVALID_TRANSITION", "Müşteri zaten bu güven durumunda.");
        customer.setTrustState(request.trustState());
        customer.setBlockedReason(request.trustState() == CustomerTrustState.BLOCKED ? reason : null);
        customer.setTrustedAt(request.trustState() == CustomerTrustState.TRUSTED ? Instant.now() : null);
        customer.setTrustedBy(request.trustState() == CustomerTrustState.TRUSTED ? user.userId() : null);
        customer.setTrustReason(reason);
        customer.setEvidenceAppointmentId(null);
        customer.setUpdatedAt(Instant.now());
        em.flush();
        em.createNativeQuery("""
                insert into customer_trust_events(tenant_id,customer_id,from_state,to_state,reason,actor_id)
                values (:tenant,:customer,:before,:after,:reason,:actor)
                """).setParameter("tenant", user.tenantId()).setParameter("customer", customerId)
                .setParameter("before", before.name()).setParameter("after", request.trustState().name())
                .setParameter("reason", reason).setParameter("actor", user.userId()).executeUpdate();
        metrics.trustTransition(before.name(), request.trustState().name(), "MANUAL");
        operations.audit(user, "CUSTOMER_TRUST_CHANGED", "tenant_customer", customerId,
                Map.of("before", before.name(), "after", request.trustState().name(), "reason", Objects.toString(reason, "")));
        return new BookingDtos.TrustResponse(customer.getId(), customer.getTrustState(), customer.getCompletedCount(),
                customer.getNoShowCount(), customer.getBlockedReason(), customer.getTrustedAt(), customer.getTrustReason(),
                customer.getEvidenceAppointmentId(), customer.getVersion(), customer.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<BookingDtos.CustomerSummary> customers(AuthenticatedUser user) {
        context(user.tenantId());
        return em.createNativeQuery("""
                select c.id,a.full_name,a.email,a.phone,c.trust_state,c.completed_count,c.no_show_count,
                       c.blocked_reason,c.trusted_at,c.trust_reason,c.evidence_appointment_id,c.version,max(p.starts_at)
                from tenant_customers c join customer_accounts a on a.id=c.customer_account_id
                left join appointments p on p.tenant_id=c.tenant_id and p.customer_id=c.id
                where c.tenant_id=:tenant
                group by c.id,a.id order by max(p.starts_at) desc nulls last,a.full_name
                """).setParameter("tenant", user.tenantId()).getResultList().stream().map(raw -> {
            Object[] row=(Object[])raw;
            return new BookingDtos.CustomerSummary((UUID)row[0],(String)row[1],(String)row[2],(String)row[3],
                    CustomerTrustState.valueOf(row[4].toString()),((Number)row[5]).intValue(),((Number)row[6]).intValue(),
                    (String)row[7],toInstant(row[8]),(String)row[9],(UUID)row[10],((Number)row[11]).intValue(),toInstant(row[12]));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDtos.TrustEvent> trustEvents(AuthenticatedUser user, UUID customerId) {
        context(user.tenantId());
        Number exists=(Number)em.createNativeQuery("select count(*) from tenant_customers where tenant_id=:tenant and id=:customer")
                .setParameter("tenant",user.tenantId()).setParameter("customer",customerId).getSingleResult();
        if(exists.longValue()==0) throw missing("Müşteri profili");
        return em.createNativeQuery("""
                select e.id,e.from_state,e.to_state,e.reason,u.full_name,e.evidence_appointment_id,e.created_at
                from customer_trust_events e join users u on u.id=e.actor_id
                where e.tenant_id=:tenant and e.customer_id=:customer order by e.created_at desc,e.id desc
                """).setParameter("tenant",user.tenantId()).setParameter("customer",customerId).getResultList().stream()
                .map(raw->{Object[] row=(Object[])raw;return new BookingDtos.TrustEvent((UUID)row[0],
                        CustomerTrustState.valueOf(row[1].toString()),CustomerTrustState.valueOf(row[2].toString()),
                        (String)row[3],(String)row[4],(UUID)row[5],toInstant(row[6]));}).toList();
    }

    private BookingRequest replay(BookingRequest booking, UUID key, String action) {
        if (booking.getStatus() == BookingRequestStatus.PENDING_APPROVAL) return null;
        if (booking.getStatus() == BookingRequestStatus.EXPIRED)
            throw new ApiException("BOOKING_REQUEST_EXPIRED", "Onay süresi dolmuş. Müşteri yeniden saat seçmeli.");
        if (key.equals(booking.getDecisionKey()) && action.equals(booking.getDecisionAction())) return booking;
        throw new ApiException("BOOKING_ALREADY_DECIDED", "Rezervasyon talebi daha önce sonuçlandırılmış.");
    }

    private void requirePending(BookingRequest booking) {
        if (booking.getStatus() == BookingRequestStatus.EXPIRED)
            throw new ApiException("BOOKING_REQUEST_EXPIRED", "Onay süresi dolmuş. Müşteri yeniden saat seçmeli.");
        if (booking.getStatus() != BookingRequestStatus.PENDING_APPROVAL)
            throw new ApiException("BOOKING_ALREADY_DECIDED", "Rezervasyon talebi daha önce sonuçlandırılmış.");
        if (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(Instant.now()))
            throw new ApiException("BOOKING_REQUEST_EXPIRED", "Onay süresi dolmuş. Müşteri yeniden saat seçmeli.");
    }

    private void decide(BookingRequest booking, UUID actor, UUID key, String action) {
        booking.setHoldExpiresAt(null);
        booking.setDecisionKey(key);
        booking.setDecisionAction(action);
        booking.setDecidedBy(actor);
        booking.setDecidedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());
    }

    private BookingRequest locked(UUID tenantId, UUID id) {
        var rows = em.createNativeQuery("select id from booking_requests where tenant_id=:tenant and id=:id for update")
                .setParameter("tenant", tenantId).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw missing("Rezervasyon talebi");
        return em.find(BookingRequest.class, id);
    }

    private boolean appointmentConflict(BookingRequest booking) {
        Number count = (Number) em.createNativeQuery("""
                select count(*) from appointments where tenant_id=:tenant and staff_id=:staff
                  and status<>'CANCELLED' and starts_at<:to and busy_until>:from
                """).setParameter("tenant", booking.getTenantId()).setParameter("staff", booking.getStaffId())
                .setParameter("from", booking.getStartsAt()).setParameter("to", booking.getBusyUntil()).getSingleResult();
        return count.longValue() > 0;
    }

    private List<Object[]> rows(UUID tenantId, BookingRequestStatus status) {
        return em.createQuery("""
                select b,c,a,s,p from BookingRequest b,TenantCustomer c,CustomerAccount a,Hizmet s,Personel p
                where b.tenantId=:tenant and b.customerId=c.id and c.customerAccountId=a.id
                  and b.serviceId=s.id and b.staffId=p.id and b.status=:status
                order by b.createdAt desc
                """, Object[].class).setParameter("tenant", tenantId).setParameter("status", status).getResultList();
    }

    private Object[] row(UUID tenantId, UUID requestId) {
        return em.createQuery("""
                select b,c,a,s,p from BookingRequest b,TenantCustomer c,CustomerAccount a,Hizmet s,Personel p
                where b.tenantId=:tenant and b.id=:id and b.customerId=c.id and c.customerAccountId=a.id
                  and b.serviceId=s.id and b.staffId=p.id
                """, Object[].class).setParameter("tenant", tenantId).setParameter("id", requestId)
                .getResultStream().findFirst().orElseThrow(() -> missing("Rezervasyon talebi"));
    }

    private BookingDtos.StaffResponse response(Object[] row) {
        var b = (BookingRequest) row[0];
        var c = (TenantCustomer) row[1];
        var a = (CustomerAccount) row[2];
        var s = (Hizmet) row[3];
        var p = (Personel) row[4];
        return new BookingDtos.StaffResponse(b.getId(), c.getId(), a.getFullName(), a.getEmail(), a.getPhone(),
                c.getTrustState(), c.getCompletedCount(), c.getNoShowCount(), c.getVersion(), s.getId(), s.getName(), p.getId(),
                p.getDisplayName(), b.getStartsAt(), b.getBusyUntil(), b.getStatus(), b.getDecision(),
                b.getHoldExpiresAt(), b.getAppointmentId(), b.getNote(), b.getRejectionReason(), b.getCreatedAt(), b.getDecidedAt());
    }

    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private Personel person(UUID tenantId, UUID id) {
        return em.createQuery("select p from Personel p where p.id=:id and p.tenant.id=:tenant and p.active=true", Personel.class)
                .setParameter("id", id).setParameter("tenant", tenantId).getResultStream().findFirst().orElseThrow(() -> missing("Personel"));
    }

    private Hizmet service(UUID tenantId, UUID id) {
        return em.createQuery("select s from Hizmet s where s.id=:id and s.tenant.id=:tenant and s.deletedAt is null", Hizmet.class)
                .setParameter("id", id).setParameter("tenant", tenantId).getResultStream().findFirst().orElseThrow(() -> missing("Hizmet"));
    }

    private CustomerAccount customerAccount(UUID id) {
        var account = em.find(CustomerAccount.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (account == null || !account.isActive()) throw missing("Müşteri");
        return account;
    }

    private static UUID validKey(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw new ApiException("INVALID_KEY", "Idempotency-Key geçerli bir UUID olmalı."); }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant toInstant(Object value) {
        if(value instanceof Instant instant) return instant;
        if(value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return null;
    }
    private static ApiException missing(String name) { return new ApiException("NOT_FOUND", name + " bulunamadı."); }
}
