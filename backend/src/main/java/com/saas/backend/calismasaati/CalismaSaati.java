package com.saas.backend.calismasaati;

import com.saas.backend.personel.Personel;
import com.saas.backend.tenant.Tenant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "working_hours")
@Getter
@Setter
public class CalismaSaati {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false)
    private Personel personel;

    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}