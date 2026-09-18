package com.saas.backend.booking;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.operations.OperationsService;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CalendarClosureService {
    private final EntityManager em;
    private final OperationsService operations;

    public CalendarClosureService(EntityManager em, OperationsService operations) {
        this.em = em;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public List<Closure> list(AuthenticatedUser user, Instant from, Instant to) {
        context(user);
        range(from, to);
        return em.createNativeQuery("""
                select c.id,c.staff_id,p.display_name,c.closure_type,c.starts_at,c.ends_at,c.reason,c.version
                from calendar_closures c left join staff p on p.tenant_id=c.tenant_id and p.id=c.staff_id
                where c.tenant_id=:tenant and c.starts_at<:to and c.ends_at>:from
                order by c.starts_at,c.id
                """).setParameter("tenant", user.tenantId()).setParameter("from", from).setParameter("to", to)
                .getResultList().stream().map(value -> closure((Object[]) value)).toList();
    }

    @Transactional(readOnly = true)
    public Preview preview(AuthenticatedUser user, Request request) {
        context(user);
        validate(user, request);
        return new Preview(request.staffId() == null ? "Tüm personel" : staffName(user, request.staffId()),
                affected(user, request));
    }

    @Transactional
    public Closure create(AuthenticatedUser user, Request request) {
        context(user);
        validate(user, request);
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .setParameter("scope", "calendar-closure:" + user.tenantId()).getSingleResult();
        var conflicts = affected(user, request);
        if (!conflicts.isEmpty()) throw new ApiException("CLOSURE_CONFLICT",
                "Bu zaman aralığında kesin randevular var. Önce taşıyın veya iptal edin.",
                Map.of("appointments", conflicts));
        UUID id = (UUID) em.createNativeQuery("""
                insert into calendar_closures(tenant_id,staff_id,closure_type,starts_at,ends_at,reason,created_by)
                values (:tenant,:staff,:type,:starts,:ends,:reason,:actor) returning id
                """).setParameter("tenant", user.tenantId()).setParameter("staff", request.staffId())
                .setParameter("type", request.type().name()).setParameter("starts", request.startsAt())
                .setParameter("ends", request.endsAt()).setParameter("reason", blank(request.reason()))
                .setParameter("actor", user.userId()).getSingleResult();
        operations.audit(user, "CALENDAR_CLOSURE_CREATED", "calendar_closure", id,
                Map.of("type", request.type().name(), "startsAt", request.startsAt().toString(),
                        "endsAt", request.endsAt().toString()));
        return find(user, id);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id, int version) {
        context(user);
        int removed = em.createNativeQuery("delete from calendar_closures where tenant_id=:tenant and id=:id and version=:version and starts_at>now()")
                .setParameter("tenant", user.tenantId()).setParameter("id", id).setParameter("version", version).executeUpdate();
        if (removed != 1) throw new ApiException("STALE_VERSION", "Kapanış bulunamadı, başlamış veya başka biri tarafından değiştirildi.");
        operations.audit(user, "CALENDAR_CLOSURE_DELETED", "calendar_closure", id, Map.of());
    }

    private Closure find(AuthenticatedUser user, UUID id) {
        Object[] row = (Object[]) em.createNativeQuery("""
                select c.id,c.staff_id,p.display_name,c.closure_type,c.starts_at,c.ends_at,c.reason,c.version
                from calendar_closures c left join staff p on p.tenant_id=c.tenant_id and p.id=c.staff_id
                where c.tenant_id=:tenant and c.id=:id
                """).setParameter("tenant", user.tenantId()).setParameter("id", id).getSingleResult();
        return closure(row);
    }

    private List<AffectedAppointment> affected(AuthenticatedUser user, Request request) {
        return em.createNativeQuery("""
                select a.id,a.staff_id,p.display_name,a.customer_name,a.starts_at,a.ends_at
                from appointments a join staff p on p.tenant_id=a.tenant_id and p.id=a.staff_id
                where a.tenant_id=:tenant and a.status='CONFIRMED'
                  and (cast(:staff as uuid) is null or a.staff_id=:staff)
                  and a.starts_at<:ends and a.busy_until>:starts
                order by a.starts_at,a.id
                """).setParameter("tenant", user.tenantId()).setParameter("staff", request.staffId())
                .setParameter("starts", request.startsAt()).setParameter("ends", request.endsAt())
                .getResultList().stream().map(value -> {
                    Object[] row = (Object[]) value;
                    return new AffectedAppointment((UUID) row[0], (UUID) row[1], (String) row[2],
                            (String) row[3], instant(row[4]), instant(row[5]));
                }).toList();
    }

    private void validate(AuthenticatedUser user, Request request) {
        if (request.startsAt() == null || request.endsAt() == null || !request.startsAt().isBefore(request.endsAt())
                || request.startsAt().isBefore(Instant.now()) || Duration.between(request.startsAt(), request.endsAt()).toDays() > 7)
            throw new ApiException("INVALID_RANGE", "Kapanış gelecekte başlamalı ve en fazla 7 gün sürmeli.");
        if (request.staffId() != null) staffName(user, request.staffId());
    }

    private String staffName(AuthenticatedUser user, UUID id) {
        var rows = em.createNativeQuery("select display_name from staff where tenant_id=:tenant and id=:id and active")
                .setParameter("tenant", user.tenantId()).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "Aktif personel bulunamadı.");
        return rows.getFirst().toString();
    }

    private void range(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).toDays() > 93)
            throw new ApiException("INVALID_RANGE", "Tarih aralığı en fazla 93 gün olmalı.");
    }

    private void context(AuthenticatedUser user) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", user.tenantId().toString()).getSingleResult();
    }

    private static Closure closure(Object[] row) {
        return new Closure((UUID) row[0], (UUID) row[1], (String) row[2], Type.valueOf(row[3].toString()),
                instant(row[4]), instant(row[5]), (String) row[6], ((Number) row[7]).intValue());
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(Object value) {
        if (value instanceof Instant result) return result;
        if (value instanceof OffsetDateTime result) return result.toInstant();
        return ((Timestamp) value).toInstant();
    }

    public enum Type { BREAK, CLOSED_DAY }
    public record Request(UUID staffId, @NotNull Type type, @NotNull Instant startsAt,
                          @NotNull Instant endsAt, @Size(max = 255) String reason) {}
    public record AffectedAppointment(UUID id, UUID staffId, String staffName, String customerName,
                                      Instant startsAt, Instant endsAt) {}
    public record Preview(String scopeName, List<AffectedAppointment> appointments) {}
    public record Closure(UUID id, UUID staffId, String staffName, Type type, Instant startsAt,
                          Instant endsAt, String reason, int version) {}
}
