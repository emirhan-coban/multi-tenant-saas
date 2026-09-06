package com.saas.backend.hizmet;

import com.saas.backend.tenant.Tenant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "services")
@Getter
@Setter
public class Hizmet {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String name;

    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "buffer_min", nullable = false)
    private Integer bufferMin = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}