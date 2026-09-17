package com.saas.backend.appointment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="appointments") @Getter @Setter
public class Appointment {
    @Id @GeneratedValue private UUID id;
    @Column(nullable=false) private UUID tenantId;
    @Column(nullable=false) private UUID staffId;
    @Column(nullable=false) private UUID serviceId;
    private UUID customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    @Column(nullable=false) private boolean emailNotifications;
    @Column(nullable=false) private String source = "STAFF";
    @Column(nullable=false) private Instant startsAt;
    @Column(nullable=false) private Instant endsAt;
    @Column(nullable=false) private Instant busyUntil;
    @Column(nullable=false) private int durationMin;
    @Column(nullable=false) private int bufferMin;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status = Status.CONFIRMED;
    private String notes;
    @Column(precision=10, scale=2) private java.math.BigDecimal priceAmount;
    @Column(nullable=false, updatable=false) private Instant createdAt = Instant.now();
    @Version private Integer version;
    public enum Status { CONFIRMED, COMPLETED, CANCELLED, NO_SHOW }
}
