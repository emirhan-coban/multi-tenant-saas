package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.appointment.AppointmentRepository;
import com.saas.backend.appointment.MusaitlikHesaplayici;
import com.saas.backend.auth.ApiException;
import com.saas.backend.calismasaati.CalismaSaati;
import com.saas.backend.customer.CustomerAccount;
import com.saas.backend.customer.CustomerAccountRepository;
import com.saas.backend.hizmet.Hizmet;
import com.saas.backend.izinkaydi.IzinKaydi;
import com.saas.backend.personel.Personel;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static com.saas.backend.appointment.MusaitlikHesaplayici.ZamanAraligi;

@Service
public class BookingService {
    private final EntityManager em;
    private final AppointmentRepository appointments;
    private final CustomerAccountRepository customers;
    private final BookingRequestRateLimiter rateLimiter;
    private final com.saas.backend.operations.OperationsService operations;
    private final com.saas.backend.operations.CustomerEmailService customerEmail;
    private final ObjectMapper json;
    private final BookingHoldExpiryService holdExpiry;
    private final BookingAppointmentFactory appointmentFactory;
    private final com.saas.backend.flags.FeatureFlagService flags;
    private final InAppNotificationService notifications;
    private final com.saas.backend.ops.OpsMetrics metrics;
    private final MusaitlikHesaplayici calculator = new MusaitlikHesaplayici();
    private final com.saas.backend.operations.ClientIp clientIp;

    public BookingService(EntityManager em, AppointmentRepository appointments,
                          CustomerAccountRepository customers, BookingRequestRateLimiter rateLimiter,
                          com.saas.backend.operations.OperationsService operations, ObjectMapper json,
                          BookingHoldExpiryService holdExpiry,
                          com.saas.backend.operations.CustomerEmailService customerEmail,
                          BookingAppointmentFactory appointmentFactory,
                          com.saas.backend.flags.FeatureFlagService flags,
                          InAppNotificationService notifications,
                          com.saas.backend.ops.OpsMetrics metrics,
                          com.saas.backend.operations.ClientIp clientIp) {
        this.clientIp = clientIp;
        this.em = em;
        this.appointments = appointments;
        this.customers = customers;
        this.rateLimiter = rateLimiter;
        this.operations = operations;
        this.customerEmail = customerEmail;
        this.json = json;
        this.holdExpiry = holdExpiry;
        this.appointmentFactory = appointmentFactory;
        this.flags = flags;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public BookingDtos.PublicShop shop(String slug) {
        var tenant = tenant(slug);
        context(tenant.id());
        var services = em.createQuery("select s from Hizmet s where s.tenant.id=:tenant and s.deletedAt is null order by s.name,s.id", Hizmet.class)
                .setParameter("tenant", tenant.id()).getResultList().stream()
                .map(s -> new BookingDtos.PublicService(s.getId(), s.getName(), s.getDurationMin(), s.getBufferMin(), s.getPrice())).toList();
        var staff = em.createQuery("select p from Personel p where p.tenant.id=:tenant and p.active=true order by p.displayName,p.id", Personel.class)
                .setParameter("tenant", tenant.id()).getResultList().stream()
                .map(p -> new BookingDtos.PublicStaff(p.getId(), p.getDisplayName(), p.getChairKey())).toList();
        return new BookingDtos.PublicShop(tenant.id(), tenant.slug(), tenant.name(), zone(tenant.timezone()).getId(), services, staff);
    }

    @Transactional
    public BookingDtos.Availability availability(String slug, UUID serviceId, UUID staffId, LocalDate date) {
        var tenant = tenant(slug);
        context(tenant.id());
        holdExpiry.expire(tenant.id());
        var service = service(tenant.id(), serviceId);
        person(tenant.id(), staffId);
        var timezone = zone(tenant.timezone());
        var policy = policy(tenant.id());
        validateDate(date, timezone, policy);
        return new BookingDtos.Availability(timezone.getId(), date,
                slots(tenant.id(), staffId, date, timezone, service.getDurationMin(), service.getBufferMin(), null, policy).stream()
                        .map(slot -> new BookingDtos.Slot(slot.startsAt(), slot.endsAt())).toList());
    }

    @Transactional
    public BookingDtos.Response create(UUID accountId, String key, BookingDtos.CreateRequest request, HttpServletRequest http) {
        UUID idempotencyKey = validKey(key);
        var tenant = tenant(request.tenantSlug());
        context(tenant.id());
        holdExpiry.expire(tenant.id());
        String hash = hash(json.writeValueAsString(request));

        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", "customer-booking:" + tenant.id() + ":" + accountId + ":" + idempotencyKey).getSingleResult();
        var previous = existing(tenant.id(), accountId, idempotencyKey.toString());
        if (previous != null) {
            if (!previous.getRequestHash().equals(hash))
                throw new ApiException("IDEMPOTENCY_CONFLICT", "Bu istek anahtarı farklı bilgilerle kullanılmış");
            return response(previous, tenant.slug(), trust(previous.getCustomerId()).getTrustState());
        }

        rateLimiter.check(tenant.id(), clientIp.resolve(http));
        rateLimiter.checkAccount(tenant.id(), accountId);
        var customer = lockActiveCustomer(accountId);
        var tenantCustomer = tenantCustomer(tenant.id(), accountId);
        lockCustomer(tenantCustomer);
        if (tenantCustomer.getTrustState() == CustomerTrustState.BLOCKED) {
            metrics.abuse("blocked_customer");
            throw new ApiException("CUSTOMER_BLOCKED", "Bu işletmede çevrim içi rezervasyon oluşturamazsınız.");
        }

        var service = service(tenant.id(), request.serviceId());
        person(tenant.id(), request.staffId());
        validateSceneSelection(tenant.id(), request);
        var timezone = zone(tenant.timezone());
        var policy = policy(tenant.id());
        validateStart(request.startsAt(), timezone, policy);
        var localDate = request.startsAt().atZone(timezone).toLocalDate();

        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", slotLock(request.staffId(), localDate)).getSingleResult();
        if (slots(tenant.id(), request.staffId(), localDate, timezone, service.getDurationMin(), service.getBufferMin(), null, policy).stream()
                .noneMatch(slot -> slot.startsAt().equals(request.startsAt())))
            throw new ApiException("SLOT_UNAVAILABLE", "Bu saat artık müsait değil. Müsait saatleri yenileyin.");

        var booking = new BookingRequest();
        booking.setTenantId(tenant.id());
        booking.setCustomerId(tenantCustomer.getId());
        booking.setStaffId(request.staffId());
        booking.setServiceId(request.serviceId());
        booking.setStartsAt(request.startsAt());
        booking.setBusyUntil(request.startsAt().plusSeconds((service.getDurationMin() + service.getBufferMin()) * 60L));
        booking.setNote(blankToNull(request.note()));
        booking.setIdempotencyKey(idempotencyKey.toString());
        booking.setRequestHash(hash);
        booking.setSource(source(request));
        booking.setSceneVersion(request.sceneVersion());
        booking.setChairKey(blankToNull(request.chairKey()));

        enforceDailyLimit(tenant.id(), tenantCustomer.getId(), timezone, policy.maxDailyBookings());
        // İşletme ayarı + global/işletme kill switch: ikisi de açık olmadıkça güvenilir müşteri de onaya düşer.
        boolean autoConfirm = tenantCustomer.getTrustState() == CustomerTrustState.TRUSTED
                && policy.trustedAutoConfirmEnabled()
                && flags.enabled(com.saas.backend.flags.FeatureFlag.TRUSTED_AUTO_CONFIRM, tenant.id());
        if (autoConfirm) {
            enforceFutureLimit(tenant.id(), tenantCustomer.getId(), policy.maxFutureAppointments());
            enforceCustomerAvailability(tenant.id(), tenantCustomer.getId(), booking.getStartsAt(), booking.getBusyUntil());
            operations.checkAppointmentQuota(tenant.id());
            var appointment = appointmentFactory.create(tenant.id(), tenantCustomer.getId(), customer, service, booking);
            appointments.saveAndFlush(appointment);
            operations.auditCustomer(tenant.id(), accountId, "APPOINTMENT_CREATED", "appointment", appointment.getId(),
                    Map.of("source", "CUSTOMER_AUTO_CONFIRM", "startsAt", appointment.getStartsAt().toString()));
            customerEmail.queueForCustomer(tenant.id(), appointment, "CUSTOMER_CONFIRMATION");
            booking.setDecision(BookingDecision.AUTO_CONFIRM);
            booking.setStatus(BookingRequestStatus.AUTO_CONFIRMED);
            booking.setAppointmentId(appointment.getId());
        } else {
            if (activeRequestCount(tenant.id(), tenantCustomer.getId()) >= BookingDefaults.MAX_ACTIVE_REQUESTS_PER_CUSTOMER)
                throw new ApiException("ACTIVE_REQUEST_LIMIT", "Önce mevcut onay bekleyen talebinizin sonuçlanmasını bekleyin.");
            booking.setDecision(BookingDecision.REQUIRE_APPROVAL);
            booking.setStatus(BookingRequestStatus.PENDING_APPROVAL);
            booking.setHoldExpiresAt(Instant.now().plus(Duration.ofMinutes(policy.requestExpiryMinutes())));
        }
        em.persist(booking);
        em.flush();
        metrics.bookingDecision(booking.getDecision().name(), booking.getSource().name());
        String when = notificationTime(booking.getStartsAt(), timezone);
        if (autoConfirm) {
            notifications.notifyCustomer(tenant.id(), accountId, "APPOINTMENT_CONFIRMED", "Randevun kesinleşti",
                    tenant.name() + " · " + service.getName() + " · " + when, "booking_request", booking.getId());
            notifications.notifyManagers(tenant.id(), "APPOINTMENT_AUTO_CONFIRMED", "Otomatik onaylanan randevu",
                    service.getName() + " · " + when, "booking_request", booking.getId());
        } else {
            notifications.notifyManagers(tenant.id(), "BOOKING_REQUEST_CREATED", "Yeni rezervasyon talebi",
                    service.getName() + " · " + when + " onay bekliyor", "booking_request", booking.getId());
        }
        return response(booking, tenant.slug(), tenantCustomer.getTrustState());
    }

    @Transactional
    public BookingDtos.Response own(UUID accountId, UUID requestId) {
        var rows = em.createNativeQuery("select tenant_id,tenant_slug from resolve_customer_booking(:id,:account)")
                .setParameter("id", requestId).setParameter("account", accountId).getResultList();
        if (rows.isEmpty()) throw missing("Rezervasyon talebi");
        Object[] owner = (Object[]) rows.getFirst();
        UUID tenantId = (UUID) owner[0];
        String slug = (String) owner[1];
        context(tenantId);
        holdExpiry.expire(tenantId);
        var booking = em.find(BookingRequest.class, requestId);
        if (booking == null) throw missing("Rezervasyon talebi");
        return response(booking, slug, trust(booking.getCustomerId()).getTrustState());
    }

    private List<ZamanAraligi> slots(UUID tenantId, UUID staffId, LocalDate date, ZoneId zone, int duration, int buffer,
                                    UUID excludeAppointmentId, BookingPolicy policy) {
        var from = date.atStartOfDay(zone).toInstant();
        var to = date.plusDays(1).atStartOfDay(zone).toInstant();
        var hours = em.createQuery("select h from CalismaSaati h where h.tenant.id=:tenant and h.personel.id=:staff and h.dayOfWeek=:day", CalismaSaati.class)
                .setParameter("tenant", tenantId).setParameter("staff", staffId).setParameter("day", date.getDayOfWeek().getValue() - 1)
                .getResultList().stream().flatMap(h -> {
                    var begin = date.atTime(h.getStartTime()).atZone(zone).toInstant();
                    var end = date.atTime(h.getEndTime()).atZone(zone).toInstant();
                    return begin.isBefore(end) ? java.util.stream.Stream.of(new ZamanAraligi(begin, end)) : java.util.stream.Stream.<ZamanAraligi>empty();
                }).toList();
        var leave = em.createQuery("select t from IzinKaydi t where t.tenant.id=:tenant and t.personel.id=:staff and t.startsAt<:to and t.endsAt>:from", IzinKaydi.class)
                .setParameter("tenant", tenantId).setParameter("staff", staffId).setParameter("from", from).setParameter("to", to)
                .getResultList().stream().map(t -> new ZamanAraligi(t.getStartsAt(), t.getEndsAt())).toList();
        var blocked = new ArrayList<>(leave);
        for (Object value : em.createNativeQuery("""
                select starts_at,ends_at from calendar_closures
                where tenant_id=:tenant and (staff_id is null or staff_id=:staff)
                  and starts_at<:to and ends_at>:from
                """).setParameter("tenant", tenantId).setParameter("staff", staffId)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            Object[] row = (Object[]) value;
            blocked.add(new ZamanAraligi(asInstant(row[0]), asInstant(row[1])));
        }
        var busy = new ArrayList<>(appointments.busy(tenantId, staffId, from, to).stream()
                .filter(a -> excludeAppointmentId == null || !a.getId().equals(excludeAppointmentId))
                .map(a -> new ZamanAraligi(a.getStartsAt(), a.getBusyUntil())).toList());
        var earliest = Instant.now().plus(Duration.ofMinutes(policy.minimumLeadMinutes()));
        return calculator.hesapla(hours, blocked, busy, duration, buffer).stream()
                .filter(slot -> !slot.startsAt().isBefore(earliest)).toList();
    }

    private TenantCustomer tenantCustomer(UUID tenantId, UUID accountId) {
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", "tenant-customer:" + tenantId + ":" + accountId).getSingleResult();
        var found = em.createQuery("select c from TenantCustomer c where c.tenantId=:tenant and c.customerAccountId=:account", TenantCustomer.class)
                .setParameter("tenant", tenantId).setParameter("account", accountId).getResultStream().findFirst();
        if (found.isPresent()) return found.get();
        var customer = new TenantCustomer();
        customer.setTenantId(tenantId);
        customer.setCustomerAccountId(accountId);
        em.persist(customer);
        em.flush();
        return customer;
    }

    private BookingRequest existing(UUID tenantId, UUID accountId, String key) {
        return em.createQuery("""
                select b from BookingRequest b,TenantCustomer c
                where b.tenantId=:tenant and b.customerId=c.id and c.customerAccountId=:account and b.idempotencyKey=:key
                """, BookingRequest.class).setParameter("tenant", tenantId).setParameter("account", accountId)
                .setParameter("key", key).getResultStream().findFirst().orElse(null);
    }

    private long activeRequestCount(UUID tenantId, UUID customerId) {
        return em.createQuery("select count(b) from BookingRequest b where b.tenantId=:tenant and b.customerId=:customer and b.status=:status", Long.class)
                .setParameter("tenant", tenantId).setParameter("customer", customerId)
                .setParameter("status", BookingRequestStatus.PENDING_APPROVAL).getSingleResult();
    }

    private void lockCustomer(TenantCustomer customer) {
        em.lock(customer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        em.refresh(customer);
    }

    private BookingPolicy policy(UUID tenantId) {
        var rows = em.createNativeQuery("""
                select trusted_auto_confirm_enabled,max_future_appointments,max_daily_bookings,
                       booking_horizon_days,minimum_lead_minutes,request_expiry_minutes,cancellation_cutoff_minutes
                from booking_settings where tenant_id=:tenant
                """).setParameter("tenant", tenantId).getResultList();
        if (rows.isEmpty()) return new BookingPolicy(true, BookingDefaults.MAX_FUTURE_APPOINTMENTS,
                BookingDefaults.MAX_DAILY_BOOKINGS, BookingDefaults.BOOKING_HORIZON_DAYS,
                BookingDefaults.MINIMUM_LEAD_MINUTES, BookingDefaults.APPROVAL_HOLD_MINUTES,
                BookingDefaults.CUSTOMER_CANCELLATION_MINUTES);
        Object[] row = (Object[]) rows.getFirst();
        return new BookingPolicy((Boolean) row[0], ((Number) row[1]).intValue(), ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(), ((Number) row[4]).intValue(), ((Number) row[5]).intValue(),
                ((Number) row[6]).intValue());
    }

    private void enforceFutureLimit(UUID tenantId, UUID customerId, int limit) {
        Long count = em.createQuery("""
                select count(a) from Appointment a where a.tenantId=:tenant and a.customerId=:customer
                  and a.status=:status and a.startsAt>current_timestamp
                """, Long.class).setParameter("tenant", tenantId).setParameter("customer", customerId)
                .setParameter("status", Appointment.Status.CONFIRMED).getSingleResult();
        if (count >= limit) throw new ApiException("FUTURE_APPOINTMENT_LIMIT",
                "Aynı işletmede en fazla " + limit + " gelecek randevunuz olabilir.");
    }

    private void enforceDailyLimit(UUID tenantId, UUID customerId, ZoneId zone, int limit) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        Long count = em.createQuery("""
                select count(b) from BookingRequest b where b.tenantId=:tenant and b.customerId=:customer
                  and b.createdAt>=:from and b.createdAt<:to
                """, Long.class).setParameter("tenant", tenantId).setParameter("customer", customerId)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        if (count >= limit) throw new ApiException("DAILY_BOOKING_LIMIT",
                "Günlük çevrim içi rezervasyon sınırına ulaştınız. Yarın tekrar deneyin.");
    }

    private void enforceCustomerAvailability(UUID tenantId, UUID customerId, Instant startsAt, Instant busyUntil) {
        Long count = em.createQuery("""
                select count(a) from Appointment a where a.tenantId=:tenant and a.customerId=:customer
                  and a.status=:status and a.startsAt<:busyUntil and a.busyUntil>:startsAt
                """, Long.class).setParameter("tenant", tenantId).setParameter("customer", customerId)
                .setParameter("status", Appointment.Status.CONFIRMED).setParameter("startsAt", startsAt)
                .setParameter("busyUntil", busyUntil).getSingleResult();
        if (count > 0) throw new ApiException("CUSTOMER_TIME_CONFLICT", "Bu saatte başka bir randevunuz bulunuyor.");
    }

    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant', :tenant, true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private PublicTenant tenant(String slug) {
        if (slug == null || slug.isBlank()) throw missing("İşletme");
        var rows = em.createNativeQuery("select id,name,slug,timezone from resolve_public_tenant(:slug)")
                .setParameter("slug", slug).getResultList();
        if (rows.isEmpty()) throw missing("İşletme");
        Object[] row = (Object[]) rows.getFirst();
        return new PublicTenant((UUID) row[0], (String) row[1], (String) row[2], (String) row[3]);
    }

    private CustomerAccount activeCustomer(UUID id) {
        return customers.findById(id).filter(CustomerAccount::isActive).orElseThrow(() -> missing("Müşteri"));
    }

    private CustomerAccount lockActiveCustomer(UUID id) {
        var account = em.find(CustomerAccount.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (account == null || !account.isActive()) throw missing("Müşteri");
        return account;
    }

    private TenantCustomer trust(UUID id) {
        var value = em.find(TenantCustomer.class, id);
        if (value == null) throw missing("Müşteri profili");
        return value;
    }

    private Personel person(UUID tenantId, UUID id) {
        return em.createQuery("select p from Personel p where p.id=:id and p.tenant.id=:tenant and p.active=true", Personel.class)
                .setParameter("id", id).setParameter("tenant", tenantId).getResultStream().findFirst().orElseThrow(() -> missing("Personel"));
    }

    private void validateSceneSelection(UUID tenantId, BookingDtos.CreateRequest request) {
        if (source(request) == BookingSource.WEB_LIST) {
            if (request.sceneVersion() != null || blankToNull(request.chairKey()) != null)
                throw new ApiException("INVALID_BOOKING_SOURCE", "Liste rezervasyonunda sahne bilgisi gönderilemez.");
            return;
        }
        if (!flags.enabled(com.saas.backend.flags.FeatureFlag.BOOKING_3D, tenantId)) {
            metrics.featureDisabledHit(com.saas.backend.flags.FeatureFlag.BOOKING_3D.key());
            throw new ApiException("FEATURE_DISABLED", "3D koltuk seçimi şu anda kapalı. Aynı berber ve saati liste görünümünden seçebilirsiniz.",
                    Map.of("flag", com.saas.backend.flags.FeatureFlag.BOOKING_3D.key(), "fallback", "WEB_LIST"));
        }
        if (request.sceneVersion() == null || blankToNull(request.chairKey()) == null)
            throw new ApiException("INVALID_SCENE_SELECTION", "3D rezervasyonu için sahne ve koltuk seçimi zorunludur.");
        Number matches = (Number) em.createNativeQuery("""
                select count(*) from shop_scenes s join shop_chairs c on c.scene_id=s.id and c.tenant_id=s.tenant_id
                where s.tenant_id=:tenant and s.status='PUBLISHED' and s.version=:version
                  and c.chair_key=:chair and c.staff_id=:staff
                """).setParameter("tenant", tenantId).setParameter("version", request.sceneVersion())
                .setParameter("chair", request.chairKey()).setParameter("staff", request.staffId()).getSingleResult();
        if (matches.longValue() != 1)
            throw new ApiException("STALE_SCENE_SELECTION", "Dükkân düzeni değişti. Sahneyi yenileyip koltuğu tekrar seçin.");
    }

    static String notificationTime(Instant startsAt, ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMMM HH:mm", Locale.forLanguageTag("tr-TR")).format(startsAt.atZone(zone));
    }

    private static BookingSource source(BookingDtos.CreateRequest request) {
        return request.source() == null ? BookingSource.WEB_LIST : request.source();
    }

    private Hizmet service(UUID tenantId, UUID id) {
        return em.createQuery("select s from Hizmet s where s.id=:id and s.tenant.id=:tenant and s.deletedAt is null", Hizmet.class)
                .setParameter("id", id).setParameter("tenant", tenantId).getResultStream().findFirst().orElseThrow(() -> missing("Hizmet"));
    }

    private void validateDate(LocalDate date, ZoneId zone, BookingPolicy policy) {
        var today = LocalDate.now(zone);
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(policy.bookingHorizonDays())))
            throw new ApiException("INVALID_BOOKING_DATE", "Tarih bugün ile " + policy.bookingHorizonDays() + " gün arasında olmalı.");
    }

    private void validateStart(Instant start, ZoneId zone, BookingPolicy policy) {
        if (start == null || start.isBefore(Instant.now().plus(Duration.ofMinutes(policy.minimumLeadMinutes()))))
            throw new ApiException("BOOKING_TOO_EARLY", "Randevu en az " + policy.minimumLeadMinutes() + " dakika önceden alınmalı.");
        validateDate(start.atZone(zone).toLocalDate(), zone, policy);
    }

    private BookingDtos.Response response(BookingRequest booking, String slug, CustomerTrustState trustState) {
        var appointment = booking.getAppointmentId() == null ? null
                : appointments.findByIdAndTenantId(booking.getAppointmentId(), booking.getTenantId()).orElse(null);
        Instant cancellableUntil = cancellableUntil(booking, appointment);
        BookingResultType resultType = booking.getAppointmentId() == null ? BookingResultType.REQUEST : BookingResultType.APPOINTMENT;
        return new BookingDtos.Response(booking.getId(), slug, resultType, booking.getServiceId(), booking.getStaffId(),
                booking.getStartsAt(), booking.getStatus(), trustState, booking.getDecision(),
                booking.getHoldExpiresAt(), booking.getAppointmentId(),
                cancellableUntil != null && Instant.now().isBefore(cancellableUntil), cancellableUntil,
                booking.getVersion(), appointment == null ? null : appointment.getVersion(),
                appointment != null && cancellableUntil != null && Instant.now().isBefore(cancellableUntil), cancellableUntil);
    }

    private Instant cancellableUntil(BookingRequest booking, Appointment appointment) {
        if (booking.getStatus() == BookingRequestStatus.PENDING_APPROVAL) return booking.getHoldExpiresAt();
        if ((booking.getStatus() == BookingRequestStatus.AUTO_CONFIRMED || booking.getStatus() == BookingRequestStatus.APPROVED)
                && booking.getAppointmentId() != null
                && appointment != null && appointment.getStatus() == Appointment.Status.CONFIRMED)
            return booking.getStartsAt().minus(Duration.ofMinutes(policy(booking.getTenantId()).cancellationCutoffMinutes()));
        return null;
    }

    @Transactional
    public BookingDtos.Availability rescheduleAvailability(UUID tenantId, UUID staffId, UUID serviceId,
                                                            UUID appointmentId, LocalDate date) {
        context(tenantId);
        var service = service(tenantId, serviceId);
        person(tenantId, staffId);
        var timezone = operations.zone(tenantId);
        var policy = policy(tenantId);
        validateDate(date, timezone, policy);
        return new BookingDtos.Availability(timezone.getId(), date,
                slots(tenantId, staffId, date, timezone, service.getDurationMin(), service.getBufferMin(),
                        appointmentId, policy).stream().map(slot -> new BookingDtos.Slot(slot.startsAt(), slot.endsAt())).toList());
    }

    @Transactional
    public RescheduleTarget validateRescheduleTarget(UUID tenantId, UUID staffId, UUID serviceId,
                                                     UUID appointmentId, Instant startsAt) {
        context(tenantId);
        var service = service(tenantId, serviceId);
        person(tenantId, staffId);
        var timezone = operations.zone(tenantId);
        var policy = policy(tenantId);
        validateStart(startsAt, timezone, policy);
        var date = startsAt.atZone(timezone).toLocalDate();
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", slotLock(staffId, date)).getSingleResult();
        if (slots(tenantId, staffId, date, timezone, service.getDurationMin(), service.getBufferMin(),
                appointmentId, policy).stream().noneMatch(slot -> slot.startsAt().equals(startsAt)))
            throw new ApiException("SLOT_UNAVAILABLE", "Bu saat artık müsait değil. Eski randevunuz korunuyor.");
        return new RescheduleTarget(startsAt.plusSeconds(service.getDurationMin() * 60L),
                startsAt.plusSeconds((service.getDurationMin() + service.getBufferMin()) * 60L));
    }

    private static UUID validKey(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw new ApiException("INVALID_KEY", "Idempotency-Key geçerli bir UUID olmalı."); }
    }

    private static ZoneId zone(String value) {
        try { return ZoneId.of("Istanbul".equals(value) ? "Europe/Istanbul" : value); }
        catch (DateTimeException ex) { throw new ApiException("INVALID_TIMEZONE", "İşletmenin saat dilimi geçersiz."); }
    }

    public static String slotLock(UUID staffId, LocalDate date) { return "booking-slot:" + staffId + ":" + date; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant asInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
    private static ApiException missing(String name) { return new ApiException("NOT_FOUND", name + " bulunamadı."); }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private record PublicTenant(UUID id, String name, String slug, String timezone) {}
    private record BookingPolicy(boolean trustedAutoConfirmEnabled, int maxFutureAppointments, int maxDailyBookings,
                                 int bookingHorizonDays, int minimumLeadMinutes, int requestExpiryMinutes,
                                 int cancellationCutoffMinutes) {}
    public record RescheduleTarget(Instant endsAt, Instant busyUntil) {}
}
