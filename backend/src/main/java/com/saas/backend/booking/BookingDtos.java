package com.saas.backend.booking;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {
    public record PublicService(UUID id, String name, int durationMin, int bufferMin, BigDecimal price) {}
    public record PublicStaff(UUID id, String displayName, String chairKey) {}
    public record PublicShop(UUID id, String slug, String name, String timezone,
                             List<PublicService> services, List<PublicStaff> staff) {}
    public record Slot(Instant startsAt, Instant endsAt) {}
    public record Availability(String timezone, LocalDate date, List<Slot> slots) {}
    public record CreateRequest(@NotBlank @Size(max = 100) String tenantSlug,
                                @NotNull UUID serviceId,
                                @NotNull UUID staffId,
                                @NotNull Instant startsAt,
                                @Size(max = 500) String note,
                                BookingSource source,
                                @Min(1) Integer sceneVersion,
                                @Size(max = 40) String chairKey) {}
    public record Response(UUID id, String tenantSlug, BookingResultType resultType,
                           UUID serviceId, UUID staffId,
                           Instant startsAt, BookingRequestStatus status,
                           CustomerTrustState trustState, BookingDecision decision,
                           Instant holdExpiresAt, UUID appointmentId,
                           boolean canCancel, Instant cancellableUntil,
                           int version, Integer appointmentVersion,
                           boolean canReschedule, Instant reschedulableUntil) {}
    public record CustomerBooking(UUID id, String tenantSlug, String tenantName, String timezone,
                                  UUID serviceId, String serviceName, UUID staffId, String staffName,
                                  Instant startsAt, Instant busyUntil, BookingRequestStatus status,
                                  String appointmentStatus, CustomerTrustState trustState,
                                  BookingDecision decision, Instant holdExpiresAt, UUID appointmentId,
                                  boolean canCancel, Instant cancellableUntil, String rejectionReason,
                                  Instant createdAt, Instant cancelledAt, int version,
                                  Integer appointmentVersion, boolean canReschedule,
                                  Instant reschedulableUntil, UUID rescheduleRequestId,
                                  String rescheduleStatus, Instant proposedStartsAt) {}
    public record CustomerBookingSummary(int pending, int upcoming, int completed,
                                         int cancelled, int noShow) {}
    public record CustomerBookings(List<CustomerBooking> items, CustomerBookingSummary summary) {}
    public record StaffResponse(UUID id, UUID customerId, String customerName, String customerEmail,
                                String customerPhone, CustomerTrustState trustState, int completedCount,
                                int noShowCount, int trustVersion, UUID serviceId, String serviceName, UUID staffId,
                                String staffName, Instant startsAt, Instant busyUntil,
                                BookingRequestStatus status, BookingDecision decision,
                                Instant holdExpiresAt, UUID appointmentId, String note,
                                String rejectionReason, Instant createdAt, Instant decidedAt) {}
    public record RejectRequest(@Size(max = 250) String reason) {}
    public record TrustUpdate(@NotNull CustomerTrustState trustState,
                              @NotNull Integer expectedVersion,
                              @Size(max = 250) String reason) {}
    public record TrustResponse(UUID customerId, CustomerTrustState trustState,
                                int completedCount, int noShowCount,
                                String blockedReason, Instant trustedAt, String trustReason,
                                UUID evidenceAppointmentId, int version, Instant updatedAt) {}
    public record CustomerSummary(UUID customerId, String customerName, String customerEmail,
                                  String customerPhone, CustomerTrustState trustState,
                                  int completedCount, int noShowCount, String blockedReason,
                                  Instant trustedAt, String trustReason, UUID evidenceAppointmentId,
                                  int version, Instant lastAppointmentAt) {}
    public record TrustEvent(UUID id, CustomerTrustState fromState, CustomerTrustState toState,
                             String reason, String actorName, UUID evidenceAppointmentId,
                             Instant createdAt) {}
    public record CustomerCancel(@NotNull @Min(0) Integer version,
                                 @Min(0) Integer appointmentVersion) {}
    public record CustomerReschedule(@NotNull Instant startsAt,
                                     @NotNull @Min(0) Integer version,
                                     @NotNull @Min(0) Integer appointmentVersion) {}
    public record StaffReschedule(UUID id, UUID bookingRequestId, UUID appointmentId,
                                  UUID customerId, String customerName, String customerEmail,
                                  UUID staffId, String staffName, UUID serviceId, String serviceName,
                                  Instant currentStartsAt, Instant requestedStartsAt,
                                  String status, int appointmentVersion, String rejectionReason,
                                  Instant createdAt, Instant decidedAt, int version) {}

    private BookingDtos() {}
}
