package com.saas.backend.personel;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PersonelRepository extends JpaRepository<Personel, UUID> {
    List<Personel> findByActiveTrue();
}