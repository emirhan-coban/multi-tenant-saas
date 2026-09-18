package com.saas.backend.personel;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface PersonelRepository extends JpaRepository<Personel, UUID> {
    List<Personel> findByActiveTrue();
    List<Personel> findByTenantIdAndActiveTrue(UUID tenantId);
    Optional<Personel> findByIdAndTenantIdAndActiveTrue(UUID id, UUID tenantId);
    Optional<Personel> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Personel p where p.id = :id and p.tenant.id = :tenantId and p.active = true")
    Optional<Personel> findForScheduleUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
