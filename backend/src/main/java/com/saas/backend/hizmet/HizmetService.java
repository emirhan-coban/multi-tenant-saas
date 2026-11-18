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
    private final com.saas.backend.operations.OperationsService operations;

    public HizmetService(HizmetRepository hizmetRepository, TenantRepository tenantRepository, com.saas.backend.operations.OperationsService operations) {
        this.operations=operations;
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
        operations.audit(user, "SERVICE_CREATED", "service", hizmet.getId(),
            java.util.Map.of("name", request.name(), "price", request.price()));

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
            .filter(h -> h.getTenant().getId().equals(com.saas.backend.config.TenantContext.get()) && h.getDeletedAt()==null)
            .orElseThrow(() -> new com.saas.backend.auth.ApiException("NOT_FOUND", "Hizmet bulunamadı"));

        if(hizmet.getPrice().compareTo(request.price())!=0) operations.audit(actor(),"SERVICE_PRICE_CHANGED","service",id,
            java.util.Map.of("before",hizmet.getPrice(),"after",request.price()));

        hizmet.setName(request.name());
        hizmet.setDurationMin(request.durationMin());
        hizmet.setPrice(request.price());
        hizmet.setBufferMin(request.bufferMin() != null ? request.bufferMin() : 0);

        return toResponse(hizmet);
    }

    @Transactional
    public void sil(UUID id) {
        Hizmet hizmet = hizmetRepository.findById(id)
            .filter(h -> h.getTenant().getId().equals(com.saas.backend.config.TenantContext.get()) && h.getDeletedAt()==null)
            .orElseThrow(() -> new com.saas.backend.auth.ApiException("NOT_FOUND", "Hizmet bulunamadı"));

        hizmet.setDeletedAt(Instant.now());
        operations.audit(actor(),"SERVICE_DELETED","service",id,java.util.Map.of());
    }

    private AuthenticatedUser actor() {
        return (AuthenticatedUser)org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
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
