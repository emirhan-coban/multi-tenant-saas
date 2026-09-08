package com.saas.backend.appointment;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
public final class AppointmentDtos {
    public record Create(@NotNull UUID staffId, @NotNull UUID serviceId, @NotNull Instant startsAt,
                         @Size(max=200) String customerName, @Size(max=40) String customerPhone,
                         @Size(max=500) String notes, @Email @Size(max=254) String customerEmail,
                         Boolean emailNotifications) {
        public Create(UUID staffId, UUID serviceId, Instant startsAt, String customerName, String customerPhone, String notes) {
            this(staffId,serviceId,startsAt,customerName,customerPhone,notes,null,false);
        }
    }
    public record Patch(@NotNull @Min(0) Integer version, Instant startsAt, Appointment.Status status,
                        @Size(max=500) String notes) {}
    public record Response(UUID id, UUID staffId, UUID serviceId, Instant startsAt, Instant endsAt,
                           Instant busyUntil, Appointment.Status status, String customerName,
                           String customerPhone, String notes, Integer version, String customerEmail, boolean emailNotifications) {}
    public record Availability(String timezone, LocalDate date, List<MusaitlikHesaplayici.ZamanAraligi> slots) {}
    public static Response response(Appointment a) {
        return new Response(a.getId(),a.getStaffId(),a.getServiceId(),a.getStartsAt(),a.getEndsAt(),
            a.getBusyUntil(),a.getStatus(),a.getCustomerName(),a.getCustomerPhone(),a.getNotes(),a.getVersion(),a.getCustomerEmail(),a.isEmailNotifications());
    }
}
