package com.saas.backend.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_requests")
@Getter
@Setter
public class BookingRequest {
    @Id @GeneratedValue private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Column(name = "staff_id", nullable = false) private UUID staffId;
    @Column(name = "service_id", nullable = false) private UUID serviceId;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "busy_until", nullable = false) private Instant busyUntil;
    private String note;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BookingRequestStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BookingDecision decision;
    @Column(name = "hold_expires_at") private Instant holdExpiresAt;
    @Column(name = "appointment_id") private UUID appointmentId;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BookingSource source = BookingSource.WEB_LIST;
    @Column(name = "scene_version") private Integer sceneVersion;
    @Column(name = "chair_key") private String chairKey;
    @Column(name = "decided_by") private UUID decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_key") private UUID decisionKey;
    @Column(name = "decision_action") private String decisionAction;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "cancellation_key") private UUID cancellationKey;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancellation_source") private String cancellationSource;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private Integer version;
}
