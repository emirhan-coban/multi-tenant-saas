package com.saas.backend.hizmet;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface HizmetRepository extends JpaRepository<Hizmet, UUID> {
    List<Hizmet> findByDeletedAtIsNull();
    List<Hizmet> findByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
