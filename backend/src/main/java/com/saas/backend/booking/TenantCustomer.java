package com.saas.backend.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_customers")
@Getter
@Setter
public class TenantCustomer {
    @Id @GeneratedValue private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;
    @Enumerated(EnumType.STRING) @Column(name = "trust_state", nullable = false) private CustomerTrustState trustState = CustomerTrustState.NEW;
    @Column(name = "completed_count", nullable = false) private int completedCount;
    @Column(name = "no_show_count", nullable = false) private int noShowCount;
    @Column(name = "blocked_reason") private String blockedReason;
    @Column(name = "trusted_at") private Instant trustedAt;
    @Column(name = "trusted_by") private UUID trustedBy;
    @Column(name = "trust_reason") private String trustReason;
    @Column(name = "evidence_appointment_id") private UUID evidenceAppointmentId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private Integer version;
}
