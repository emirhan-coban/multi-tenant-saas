package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class VisitVerificationService {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private final EntityManager em;
    private final SecureRandom random = new SecureRandom();
    private final com.saas.backend.operations.OperationsService operations;
    private final byte[] codePepper;

    public VisitVerificationService(EntityManager em, com.saas.backend.operations.OperationsService operations,
                                    @Value("${app.jwt.secret}") String codePepper) {
        this.em = em;
        this.operations = operations;
        this.codePepper = codePepper.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public VisitVerificationDtos.Code issue(UUID accountId, UUID requestId) {
        var owner = em.createNativeQuery("select tenant_id from resolve_customer_booking(:id,:account)", UUID.class)
                .setParameter("id", requestId).setParameter("account", accountId).getResultList();
        if (owner.isEmpty()) throw missing();
        UUID tenantId = (UUID) owner.getFirst();
        context(tenantId);
        var rows = em.createNativeQuery("""
                select b.appointment_id,b.customer_id,a.starts_at,a.ends_at,a.status
                from booking_requests b join appointments a on a.tenant_id=b.tenant_id and a.id=b.appointment_id
                where b.tenant_id=:tenant and b.id=:request
                """).setParameter("tenant", tenantId).setParameter("request", requestId).getResultList();
        if (rows.isEmpty()) throw missing();
        Object[] row = (Object[]) rows.getFirst();
        UUID appointmentId = (UUID) row[0];
        UUID customerId = (UUID) row[1];
        Instant startsAt = instant(row[2]);
        Instant endsAt = instant(row[3]);
        if (!"CONFIRMED".equals(row[4].toString()))
            throw new ApiException("INVALID_TRANSITION", "Yalnızca devam eden kesin randevu için ziyaret kodu alınabilir.");
        Instant now = Instant.now();
        if (now.isBefore(startsAt.minus(Duration.ofHours(2))) || now.isAfter(endsAt.plus(Duration.ofHours(4))))
            throw new ApiException("VISIT_CODE_WINDOW_CLOSED", "Ziyaret kodu randevudan iki saat önce ve dört saat sonrasına kadar alınabilir.");
        Number verified = (Number) em.createNativeQuery("select count(*) from visit_verifications where tenant_id=:tenant and appointment_id=:appointment and verified_at is not null")
                .setParameter("tenant", tenantId).setParameter("appointment", appointmentId).getSingleResult();
        if (verified.longValue() > 0) throw new ApiException("VISIT_ALREADY_VERIFIED", "Bu randevuda müşteri hesabı zaten doğrulandı.");

        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expiresAt = now.plus(CODE_LIFETIME);
        em.createNativeQuery("""
                insert into visit_verifications(tenant_id,appointment_id,customer_id,code_hash,expires_at)
                values (:tenant,:appointment,:customer,:hash,:expires)
                on conflict (tenant_id,appointment_id) do update set
                  code_hash=excluded.code_hash,expires_at=excluded.expires_at,attempt_count=0,
                  invalidated_at=null,updated_at=now()
                """).setParameter("tenant", tenantId).setParameter("appointment", appointmentId)
                .setParameter("customer", customerId).setParameter("hash", hash(appointmentId, code))
                .setParameter("expires", expiresAt).executeUpdate();
        return new VisitVerificationDtos.Code(appointmentId, code, expiresAt);
    }

    @Transactional
    public VisitVerificationDtos.Attempt verify(AuthenticatedUser user, UUID appointmentId, String code) {
        context(user.tenantId());
        var appointment = em.createQuery("select a from Appointment a where a.tenantId=:tenant and a.id=:id", Appointment.class)
                .setParameter("tenant", user.tenantId()).setParameter("id", appointmentId)
                .getResultStream().findFirst().orElseThrow(VisitVerificationService::missing);
        if (appointment.getCustomerId() == null || appointment.getStatus() != Appointment.Status.CONFIRMED)
            throw new ApiException("INVALID_TRANSITION", "Bu randevuda doğrulanabilir bir müşteri hesabı yok.");
        var rows = em.createNativeQuery("""
                select id,code_hash,expires_at,attempt_count,verified_at,invalidated_at
                from visit_verifications where tenant_id=:tenant and appointment_id=:appointment for update
                """).setParameter("tenant", user.tenantId()).setParameter("appointment", appointmentId).getResultList();
        if (rows.isEmpty()) return failed("VISIT_CODE_NOT_FOUND", "Müşteriden güncel ziyaret kodunu isteyin.");
        Object[] row = (Object[]) rows.getFirst();
        Instant verifiedAt = instantOrNull(row[4]);
        if (verifiedAt != null) return new VisitVerificationDtos.Attempt(true,
                new VisitVerificationDtos.Status(appointmentId, true, verifiedAt), null, null);
        int attempts = ((Number) row[3]).intValue();
        boolean expired = !instant(row[2]).isAfter(Instant.now());
        boolean invalid = row[5] != null || attempts >= MAX_ATTEMPTS;
        boolean matches = !expired && !invalid && MessageDigest.isEqual(
                row[1].toString().getBytes(StandardCharsets.US_ASCII),
                hash(appointmentId, code).getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            int next = Math.min(MAX_ATTEMPTS, attempts + 1);
            em.createNativeQuery("update visit_verifications set attempt_count=:attempts,invalidated_at=case when :invalidate then now() else invalidated_at end,updated_at=now() where id=:id")
                    .setParameter("attempts", next).setParameter("invalidate", expired || invalid || next >= MAX_ATTEMPTS)
                    .setParameter("id", row[0]).executeUpdate();
            if (expired) return failed("VISIT_CODE_EXPIRED", "Ziyaret kodunun süresi doldu. Müşteri yeni kod oluşturmalı.");
            if (invalid || next >= MAX_ATTEMPTS) return failed("VISIT_CODE_LOCKED", "Kod deneme sınırı doldu. Müşteri yeni kod oluşturmalı.");
            return failed("INVALID_VISIT_CODE", "Ziyaret kodu hatalı.");
        }
        Instant now = Instant.now();
        em.createNativeQuery("update visit_verifications set verified_at=:now,verified_by=:actor,updated_at=:now where id=:id")
                .setParameter("now", now).setParameter("actor", user.userId()).setParameter("id", row[0]).executeUpdate();
        operations.audit(user, "VISIT_ACCOUNT_VERIFIED", "appointment", appointmentId,
                java.util.Map.of("customerId", appointment.getCustomerId()));
        return new VisitVerificationDtos.Attempt(true,
                new VisitVerificationDtos.Status(appointmentId, true, now), null, null);
    }

    private VisitVerificationDtos.Attempt failed(String code, String message) {
        return new VisitVerificationDtos.Attempt(false, null, code, message);
    }
    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }
    private String hash(UUID appointmentId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codePepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((appointmentId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private static Instant instant(Object value) {
        var result = instantOrNull(value);
        if (result == null) throw new IllegalStateException("Timestamp cannot be null");
        return result;
    }
    private static Instant instantOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime valueWithZone) return valueWithZone.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported timestamp value");
    }
    private static ApiException missing() { return new ApiException("NOT_FOUND", "Randevu bulunamadı."); }
}
