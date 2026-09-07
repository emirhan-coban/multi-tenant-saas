package com.saas.backend.calismasaati;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CalismaSaatiRepository extends JpaRepository<CalismaSaati, UUID> {
    List<CalismaSaati> findByPersonelIdAndTenantIdOrderByDayOfWeek(UUID personelId, UUID tenantId);
}