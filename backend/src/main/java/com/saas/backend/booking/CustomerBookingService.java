package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.auth.ApiException;
import com.saas.backend.operations.CustomerEmailService;
import com.saas.backend.operations.OperationsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

@Service
public class CustomerBookingService {
    private final EntityManager em;
    private final BookingHoldExpiryService holdExpiry;
    private final OperationsService operations;
    private final CustomerEmailService customerEmail;
    private final BookingService bookingService;
    private final InAppNotificationService notifications;
    private final com.saas.backend.flags.FeatureFlagService flags;

    public CustomerBookingService(EntityManager em, BookingHoldExpiryService holdExpiry,
                                  OperationsService operations, CustomerEmailService customerEmail,
                                  BookingService bookingService, InAppNotificationService notifications,
                                  com.saas.backend.flags.FeatureFlagService flags) {
        this.em = em;
        this.holdExpiry = holdExpiry;
        this.operations = operations;
        this.customerEmail = customerEmail;
        this.bookingService = bookingService;
        this.notifications = notifications;
        this.flags = flags;
    }

    @Transactional
    public BookingDtos.CustomerBookings list(UUID accountId) {
        var items = new ArrayList<BookingDtos.CustomerBooking>();
        for (UUID tenantId : tenantIds(accountId)) {
            context(tenantId);
            holdExpiry.expire(tenantId);
            items.addAll(rows(tenantId, accountId));
        }
        items.sort(Comparator.comparing(BookingDtos.CustomerBooking::startsAt).reversed());
        var visible = items.stream().limit(100).toList();
        int pending = 0, upcoming = 0, completed = 0, cancelled = 0, noShow = 0;
        Instant now = Instant.now();
        for (var item : items) {
            String effective = effectiveStatus(item);
            if ("PENDING_APPROVAL".equals(effective)) pending++;
            else if ("COMPLETED".equals(effective)) completed++;
            else if ("CANCELLED".equals(effective)) cancelled++;
            else if ("NO_SHOW".equals(effective)) noShow++;
            else if (("CONFIRMED".equals(effective) || "AUTO_CONFIRMED".equals(effective) || "APPROVED".equals(effective))
                    && item.startsAt().isAfter(now)) upcoming++;
        }
        return new BookingDtos.CustomerBookings(visible,
                new BookingDtos.CustomerBookingSummary(pending, upcoming, completed, cancelled, noShow));
    }

    @Transactional
    public BookingDtos.CustomerBooking cancel(UUID accountId, UUID requestId, String key, BookingDtos.CustomerCancel request) {
        UUID cancellationKey = validKey(key);
        var owner = owner(accountId, requestId);
        context(owner.tenantId());
        holdExpiry.expire(owner.tenantId());
        var booking = locked(owner.tenantId(), requestId);
        if (booking.getStatus() == BookingRequestStatus.CANCELLED) {
            if (cancellationKey.equals(booking.getCancellationKey())) return row(owner.tenantId(), accountId, requestId);
            throw new ApiException("BOOKING_ALREADY_CANCELLED", "Rezervasyon daha önce iptal edilmiş.");
        }
        if (!booking.getVersion().equals(request.version()))
            throw new ApiException("STALE_VERSION", "Rezervasyon başka bir işlemle değişti. Randevularınızı yenileyin.");

        Instant now = Instant.now();
        Appointment appointment = null;
        if (booking.getStatus() == BookingRequestStatus.PENDING_APPROVAL) {
            if (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(now))
                throw new ApiException("BOOKING_REQUEST_EXPIRED", "Onay süresi dolmuş. Yeni bir saat seçin.");
            booking.setHoldExpiresAt(null);
        } else if (booking.getStatus() == BookingRequestStatus.AUTO_CONFIRMED
                || booking.getStatus() == BookingRequestStatus.APPROVED) {
            int cutoff = cancellationCutoff(booking.getId());
            Instant deadline = booking.getStartsAt().minus(Duration.ofMinutes(cutoff));
            if (!now.isBefore(deadline))
                throw new ApiException("CANCELLATION_WINDOW_CLOSED",
                        "Çevrim içi iptal randevudan en az " + cutoff + " dakika önce yapılabilir.");
            appointment = lockedAppointment(owner.tenantId(), booking.getAppointmentId());
            if (request.appointmentVersion() == null || !appointment.getVersion().equals(request.appointmentVersion()))
                throw new ApiException("STALE_VERSION", "Randevu başka bir işlemle değişti. Randevularınızı yenileyin.");
            if (appointment.getStatus() != Appointment.Status.CONFIRMED)
                throw new ApiException("INVALID_TRANSITION", "Sonuçlanmış veya iptal edilmiş randevu değiştirilemez.");
            appointment.setStatus(Appointment.Status.CANCELLED);
            em.createNativeQuery("update outbox set status='SKIPPED' where tenant_id=:tenant and appointment_id=:appointment and status='PENDING'")
                    .setParameter("tenant", owner.tenantId()).setParameter("appointment", appointment.getId()).executeUpdate();
        } else {
            throw new ApiException("INVALID_TRANSITION", "Bu rezervasyon talebi iptal edilemez.");
        }

        booking.setStatus(BookingRequestStatus.CANCELLED);
        booking.setCancellationKey(cancellationKey);
        booking.setCancelledAt(now);
        booking.setCancellationSource("CUSTOMER");
        booking.setUpdatedAt(now);
        em.flush();

        if (appointment == null) {
            operations.auditCustomer(owner.tenantId(), accountId, "BOOKING_REQUEST_CANCELLED", "booking_request",
                    booking.getId(), Map.of("source", "CUSTOMER", "startsAt", booking.getStartsAt().toString()));
        } else {
            operations.auditCustomer(owner.tenantId(), accountId, "APPOINTMENT_CANCELLED", "appointment",
                    appointment.getId(), Map.of("source", "CUSTOMER", "bookingRequestId", booking.getId(),
                            "startsAt", booking.getStartsAt().toString()));
            customerEmail.queueForCustomer(owner.tenantId(), appointment, "CUSTOMER_CANCELLED");
            notifications.notifyCustomer(owner.tenantId(), accountId, "APPOINTMENT_CANCELLED",
                    "Randevun iptal edildi", "İptal işlemin tamamlandı; saat yeniden müsaitliğe açıldı.",
                    "booking_request", booking.getId());
        }
        return row(owner.tenantId(), accountId, requestId);
    }

    @Transactional
    public BookingDtos.Availability rescheduleAvailability(UUID accountId, UUID requestId, java.time.LocalDate date) {
        var owner = owner(accountId, requestId);
        context(owner.tenantId());
        var booking = em.find(BookingRequest.class, requestId);
        if (booking == null || booking.getAppointmentId() == null) throw missing();
        var appointment = lockedAppointment(owner.tenantId(), booking.getAppointmentId());
        if (appointment.getStatus() != Appointment.Status.CONFIRMED) throw new ApiException("INVALID_TRANSITION", "Bu randevu taşınamaz.");
        return bookingService.rescheduleAvailability(owner.tenantId(), booking.getStaffId(), booking.getServiceId(),
                appointment.getId(), date);
    }

    @Transactional
    public BookingDtos.CustomerBooking reschedule(UUID accountId, UUID requestId, String key,
                                                   BookingDtos.CustomerReschedule request) {
        UUID idempotencyKey = validKey(key);
        var owner = owner(accountId, requestId);
        context(owner.tenantId());
        holdExpiry.expire(owner.tenantId());
        String requestHash = hash(request.startsAt() + ":" + request.version() + ":" + request.appointmentVersion());
        var replay = em.createNativeQuery("""
                select id,request_hash from booking_reschedule_requests
                where tenant_id=:tenant and customer_id in
                  (select id from tenant_customers where tenant_id=:tenant and customer_account_id=:account)
                  and idempotency_key=:key
                """).setParameter("tenant", owner.tenantId()).setParameter("account", accountId)
                .setParameter("key", idempotencyKey).getResultList();
        if (!replay.isEmpty()) {
            Object[] previous = (Object[]) replay.getFirst();
            if (!requestHash.equals(previous[1]))
                throw new ApiException("IDEMPOTENCY_CONFLICT", "Bu istek anahtarı farklı bilgilerle kullanılmış");
            return row(owner.tenantId(), accountId, requestId);
        }

        var booking = locked(owner.tenantId(), requestId);
        if (!booking.getVersion().equals(request.version())) throw stale();
        if (booking.getAppointmentId() == null || (booking.getStatus() != BookingRequestStatus.AUTO_CONFIRMED
                && booking.getStatus() != BookingRequestStatus.APPROVED))
            throw new ApiException("INVALID_TRANSITION", "Yalnızca kesinleşmiş randevu taşınabilir.");
        var appointment = lockedAppointment(owner.tenantId(), booking.getAppointmentId());
        if (appointment.getStatus() != Appointment.Status.CONFIRMED) throw new ApiException("INVALID_TRANSITION", "Bu randevu taşınamaz.");
        if (!appointment.getVersion().equals(request.appointmentVersion())) throw stale();
        ensureCutoff(owner.tenantId(), booking.getStartsAt());

        var profile = em.find(TenantCustomer.class, booking.getCustomerId());
        em.lock(profile, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(profile);
        if (profile.getTrustState() == CustomerTrustState.BLOCKED)
            throw new ApiException("CUSTOMER_BLOCKED", "Bu işletmede çevrim içi taşıma yapamazsınız.");
        var target = bookingService.validateRescheduleTarget(owner.tenantId(), booking.getStaffId(), booking.getServiceId(),
                appointment.getId(), request.startsAt());
        boolean automatic = profile.getTrustState() == CustomerTrustState.TRUSTED && trustedAutoConfirm(owner.tenantId());

        if (automatic) {
            Instant previous = appointment.getStartsAt();
            appointment.setStartsAt(request.startsAt());
            appointment.setEndsAt(target.endsAt());
            appointment.setBusyUntil(target.busyUntil());
            booking.setStartsAt(request.startsAt());
            booking.setBusyUntil(target.busyUntil());
            booking.setUpdatedAt(Instant.now());
            em.flush();
            em.createNativeQuery("""
                    insert into booking_reschedule_requests(tenant_id,booking_request_id,appointment_id,customer_id,
                      requested_starts_at,requested_busy_until,appointment_version,status,idempotency_key,request_hash,decided_at)
                    values (:tenant,:booking,:appointment,:customer,:starts,:busy,:version,'APPROVED',:key,:hash,now())
                    """).setParameter("tenant", owner.tenantId()).setParameter("booking", booking.getId())
                    .setParameter("appointment", appointment.getId()).setParameter("customer", booking.getCustomerId())
                    .setParameter("starts", request.startsAt()).setParameter("busy", target.busyUntil())
                    .setParameter("version", request.appointmentVersion()).setParameter("key", idempotencyKey)
                    .setParameter("hash", requestHash).executeUpdate();
            operations.auditCustomer(owner.tenantId(), accountId, "APPOINTMENT_RESCHEDULED", "appointment",
                    appointment.getId(), Map.of("from", previous.toString(), "to", request.startsAt().toString(), "source", "CUSTOMER"));
            customerEmail.queueForCustomer(owner.tenantId(), appointment, "CUSTOMER_RESCHEDULED");
            notifications.notifyCustomer(owner.tenantId(), accountId, "APPOINTMENT_RESCHEDULED",
                    "Randevun taşındı", "Yeni randevu saatin kesinleşti.", "booking_request", booking.getId());
        } else {
            long active = ((Number) em.createNativeQuery("""
                    select count(*) from booking_reschedule_requests
                    where tenant_id=:tenant and booking_request_id=:booking and status='PENDING_APPROVAL'
                    """).setParameter("tenant", owner.tenantId()).setParameter("booking", booking.getId()).getSingleResult()).longValue();
            if (active > 0) throw new ApiException("ACTIVE_RESCHEDULE_LIMIT", "Bu randevu için zaten bekleyen bir taşıma talebi var.");
            UUID rescheduleId = (UUID) em.createNativeQuery("""
                    insert into booking_reschedule_requests(tenant_id,booking_request_id,appointment_id,customer_id,
                      requested_starts_at,requested_busy_until,appointment_version,idempotency_key,request_hash)
                    values (:tenant,:booking,:appointment,:customer,:starts,:busy,:version,:key,:hash) returning id
                    """).setParameter("tenant", owner.tenantId()).setParameter("booking", booking.getId())
                    .setParameter("appointment", appointment.getId()).setParameter("customer", booking.getCustomerId())
                    .setParameter("starts", request.startsAt()).setParameter("busy", target.busyUntil())
                    .setParameter("version", request.appointmentVersion()).setParameter("key", idempotencyKey)
                    .setParameter("hash", requestHash).getSingleResult();
            notifications.notifyManagers(owner.tenantId(), "RESCHEDULE_REQUESTED", "Yeni taşıma talebi",
                    "Bir müşteri mevcut randevusu için yeni saat istedi.", "booking_reschedule_request", rescheduleId);
        }
        return row(owner.tenantId(), accountId, requestId);
    }

    @Transactional(readOnly = true)
    public String calendar(UUID accountId, UUID requestId) {
        var owner = owner(accountId, requestId);
        context(owner.tenantId());
        var item = row(owner.tenantId(), accountId, requestId);
        if (item.appointmentId() == null || !"CONFIRMED".equals(item.appointmentStatus()))
            throw new ApiException("INVALID_TRANSITION", "Yalnızca kesinleşmiş randevu takvime eklenebilir.");
        String uid = item.appointmentId() + "@tenant-os";
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Tenant OS//Booking//TR\r\nBEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\nDTSTAMP:" + ics(Instant.now()) + "\r\nDTSTART:" + ics(item.startsAt())
                + "\r\nDTEND:" + ics(item.busyUntil()) + "\r\nSUMMARY:" + escapeIcs(item.serviceName() + " - " + item.tenantName())
                + "\r\nDESCRIPTION:" + escapeIcs("Berber: " + item.staffName()) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private List<UUID> tenantIds(UUID accountId) {
        return em.createNativeQuery("select tenant_id from resolve_customer_booking_tenants(:account)", UUID.class)
                .setParameter("account", accountId).getResultList();
    }

    private Owner owner(UUID accountId, UUID requestId) {
        var rows = em.createNativeQuery("select tenant_id,tenant_slug from resolve_customer_booking(:id,:account)")
                .setParameter("id", requestId).setParameter("account", accountId).getResultList();
        if (rows.isEmpty()) throw missing();
        Object[] values = (Object[]) rows.getFirst();
        return new Owner((UUID) values[0], (String) values[1]);
    }

    private BookingRequest locked(UUID tenantId, UUID requestId) {
        var result = em.createQuery("select b from BookingRequest b where b.tenantId=:tenant and b.id=:id", BookingRequest.class)
                .setParameter("tenant", tenantId).setParameter("id", requestId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
        return result.orElseThrow(CustomerBookingService::missing);
    }

    private Appointment lockedAppointment(UUID tenantId, UUID appointmentId) {
        if (appointmentId == null) throw missing();
        return em.createQuery("select a from Appointment a where a.tenantId=:tenant and a.id=:id", Appointment.class)
                .setParameter("tenant", tenantId).setParameter("id", appointmentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                .orElseThrow(CustomerBookingService::missing);
    }

    @SuppressWarnings("unchecked")
    private List<BookingDtos.CustomerBooking> rows(UUID tenantId, UUID accountId) {
        return em.createNativeQuery("""
                select b.id,t.slug,t.name,t.timezone,s.id,s.name,p.id,p.display_name,
                       b.starts_at,b.busy_until,b.status,a.status,c.trust_state,b.decision,
                       b.hold_expires_at,b.appointment_id,b.rejection_reason,b.created_at,b.cancelled_at,
                       b.version,a.version,rr.id,rr.status,rr.requested_starts_at
                from booking_requests b
                join tenant_customers c on c.tenant_id=b.tenant_id and c.id=b.customer_id
                join tenants t on t.id=b.tenant_id
                join services s on s.tenant_id=b.tenant_id and s.id=b.service_id
                join staff p on p.tenant_id=b.tenant_id and p.id=b.staff_id
                left join appointments a on a.tenant_id=b.tenant_id and a.id=b.appointment_id
                left join lateral (select r.id,r.status,r.requested_starts_at from booking_reschedule_requests r
                  where r.tenant_id=b.tenant_id and r.booking_request_id=b.id
                  order by r.created_at desc,r.id desc limit 1) rr on true
                where b.tenant_id=:tenant and c.customer_account_id=:account
                order by b.starts_at desc,b.id desc
                """).setParameter("tenant", tenantId).setParameter("account", accountId).getResultList().stream()
                .map(value -> map((Object[]) value)).toList();
    }

    private BookingDtos.CustomerBooking row(UUID tenantId, UUID accountId, UUID requestId) {
        return rows(tenantId, accountId).stream().filter(item -> item.id().equals(requestId)).findFirst()
                .orElseThrow(CustomerBookingService::missing);
    }

    private BookingDtos.CustomerBooking map(Object[] value) {
        var status = BookingRequestStatus.valueOf(value[10].toString());
        Instant start = instant(value[8]);
        Instant hold = instantOrNull(value[14]);
        String appointmentStatus = value[11] == null ? null : value[11].toString();
        Instant deadline = status == BookingRequestStatus.PENDING_APPROVAL ? hold
                : (status == BookingRequestStatus.AUTO_CONFIRMED || status == BookingRequestStatus.APPROVED)
                    && "CONFIRMED".equals(appointmentStatus)
                ? start.minus(Duration.ofMinutes(cancellationCutoff((UUID) value[0]))) : null;
        return new BookingDtos.CustomerBooking((UUID) value[0], (String) value[1], (String) value[2],
                normalizedZone((String) value[3]), (UUID) value[4], (String) value[5], (UUID) value[6],
                (String) value[7], start, instant(value[9]), status, appointmentStatus,
                CustomerTrustState.valueOf(value[12].toString()), BookingDecision.valueOf(value[13].toString()),
                hold, (UUID) value[15], deadline != null && Instant.now().isBefore(deadline), deadline,
                (String) value[16], instant(value[17]), instantOrNull(value[18]), ((Number) value[19]).intValue(),
                value[20] == null ? null : ((Number) value[20]).intValue(),
                deadline != null && Instant.now().isBefore(deadline) && "CONFIRMED".equals(appointmentStatus), deadline,
                (UUID) value[21], value[22] == null ? null : value[22].toString(), instantOrNull(value[23]));
    }

    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private static String effectiveStatus(BookingDtos.CustomerBooking item) {
        if (item.appointmentStatus() != null && !"CONFIRMED".equals(item.appointmentStatus())) return item.appointmentStatus();
        return item.status().name();
    }

    private static UUID validKey(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw new ApiException("INVALID_KEY", "Idempotency-Key geçerli bir UUID olmalı."); }
    }

    private static Instant instant(Object value) {
        Instant result = instantOrNull(value);
        if (result == null) throw new IllegalStateException("Timestamp cannot be null");
        return result;
    }

    private static Instant instantOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }

    private static String normalizedZone(String value) {
        return "Istanbul".equals(value) ? "Europe/Istanbul" : value;
    }

    private static ApiException missing() {
        return new ApiException("NOT_FOUND", "Rezervasyon talebi bulunamadı.");
    }

    private int cancellationCutoff(UUID bookingId) {
        return ((Number) em.createNativeQuery("""
                select s.cancellation_cutoff_minutes from booking_settings s
                join booking_requests b on b.tenant_id=s.tenant_id where b.id=:booking
                """).setParameter("booking", bookingId).getSingleResult()).intValue();
    }

    private void ensureCutoff(UUID tenantId, Instant startsAt) {
        int cutoff = ((Number) em.createNativeQuery("select cancellation_cutoff_minutes from booking_settings where tenant_id=:tenant")
                .setParameter("tenant", tenantId).getSingleResult()).intValue();
        if (!Instant.now().isBefore(startsAt.minus(Duration.ofMinutes(cutoff))))
            throw new ApiException("RESCHEDULE_WINDOW_CLOSED", "Çevrim içi taşıma süresi doldu. İşletmeyle iletişime geçin.");
    }

    private boolean trustedAutoConfirm(UUID tenantId) {
        return (Boolean) em.createNativeQuery("select trusted_auto_confirm_enabled from booking_settings where tenant_id=:tenant")
                .setParameter("tenant", tenantId).getSingleResult()
                && flags.enabled(com.saas.backend.flags.FeatureFlag.TRUSTED_AUTO_CONFIRM, tenantId);
    }

    private static ApiException stale() { return new ApiException("STALE_VERSION", "Randevu başka bir işlemle değişti. Randevularınızı yenileyin."); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String ics(Instant value) { return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(value); }
    private static String escapeIcs(String value) { return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n"); }

    private record Owner(UUID tenantId, String tenantSlug) {}
}
