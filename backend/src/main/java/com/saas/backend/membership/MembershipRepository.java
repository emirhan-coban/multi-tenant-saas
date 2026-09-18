package com.saas.backend.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserIdAndActiveTrue(UUID userId);
    Optional<Membership> findByUserIdAndTenantId(UUID userId, UUID tenantId);
}
