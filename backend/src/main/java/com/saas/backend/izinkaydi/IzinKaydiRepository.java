package com.saas.backend.izinkaydi;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface IzinKaydiRepository extends JpaRepository<IzinKaydi, UUID> {
    List<IzinKaydi> findByPersonelId(UUID personelId);
}