package com.saas.backend.appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.*;
public interface AppointmentRepository extends JpaRepository<Appointment,UUID> {
    Optional<Appointment> findByIdAndTenantId(UUID id, UUID tenantId);
    @Query("select a from Appointment a where a.tenantId=:tenant and (:staff is null or a.staffId=:staff) and a.startsAt<:to and a.endsAt>:from order by a.startsAt,a.id")
    List<Appointment> list(UUID tenant, UUID staff, Instant from, Instant to);
    @Query("select a from Appointment a where a.tenantId=:tenant and a.staffId=:staff and a.status<>com.saas.backend.appointment.Appointment.Status.CANCELLED and a.startsAt<:to and a.busyUntil>:from")
    List<Appointment> busy(UUID tenant, UUID staff, Instant from, Instant to);
}

