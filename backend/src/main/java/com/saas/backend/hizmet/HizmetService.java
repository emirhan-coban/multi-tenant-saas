package com.saas.backend.hizmet;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class HizmetService {

    private final HizmetRepository hizmetRepository;
    private final TenantRepository tenantRepository;

    public HizmetService(HizmetRepository hizmetRepository, TenantRepository tenantRepository) {
        this.hizmetRepository = hizmetRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public HizmetDtos.HizmetResponse olustur(AuthenticatedUser user, HizmetDtos.HizmetOlusturRequest request) {
        Tenant tenant = tenantRepository.getReferenceById(user.tenantId());

        Hizmet hizmet = new Hizmet();
        hizmet.setTenant(tenant);
        hizmet.setName(request.name());
        hizmet.setDurationMin(request.durationMin());
        hizmet.setPrice(request.price());
        hizmet.setBufferMin(request.bufferMin() != null ? request.bufferMin() : 0);

        hizmetRepository.save(hizmet);

        return toResponse(hizmet);
    }

    @Transactional(readOnly = true)
    public List<HizmetDtos.HizmetResponse> listele() {
        return hizmetRepository.findByTenantIdAndDeletedAtIsNull(com.saas.backend.config.TenantContext.get())
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public HizmetDtos.HizmetResponse guncelle(UUID id, HizmetDtos.HizmetGuncelleRequest request) {
        Hizmet hizmet = hizmetRepository.findById(id)
            .orElseThrow(() -> new com.saas.backend.auth.ApiException("NOT_FOUND", "Hizmet bulunamadı"));

        hizmet.setName(request.name());
        hizmet.setDurationMin(request.durationMin());
        hizmet.setPrice(request.price());
        hizmet.setBufferMin(request.bufferMin() != null ? request.bufferMin() : 0);

        return toResponse(hizmet);
    }

    @Transactional
    public void sil(UUID id) {
        Hizmet hizmet = hizmetRepository.findById(id)
            .orElseThrow(() -> new com.saas.backend.auth.ApiException("NOT_FOUND", "Hizmet bulunamadı"));

        hizmet.setDeletedAt(Instant.now());
    }

    private HizmetDtos.HizmetResponse toResponse(Hizmet hizmet) {
        return new HizmetDtos.HizmetResponse(
            hizmet.getId(),
            hizmet.getName(),
            hizmet.getDurationMin(),
            hizmet.getPrice(),
            hizmet.getBufferMin()
        );
    }
}
