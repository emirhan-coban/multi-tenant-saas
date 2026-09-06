package com.saas.backend.calismasaati;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.personel.Personel;
import com.saas.backend.personel.PersonelRepository;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CalismaSaatiService {

    private final CalismaSaatiRepository calismaSaatiRepository;
    private final PersonelRepository personelRepository;
    private final TenantRepository tenantRepository;

    public CalismaSaatiService(
            CalismaSaatiRepository calismaSaatiRepository,
            PersonelRepository personelRepository,
            TenantRepository tenantRepository
    ) {
        this.calismaSaatiRepository = calismaSaatiRepository;
        this.personelRepository = personelRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public CalismaSaatiDtos.CalismaSaatiResponse olustur(
            AuthenticatedUser user,
            CalismaSaatiDtos.CalismaSaatiRequest request
    ) {
        if (!request.startTime().isBefore(request.endTime())) {
            throw new ApiException("INVALID_RANGE", "Başlangıç saati bitiş saatinden önce olmalı");
        }

        Tenant tenant = tenantRepository.getReferenceById(user.tenantId());
        Personel personel = personelRepository.findById(request.personelId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Personel bulunamadı"));

        CalismaSaati calismaSaati = new CalismaSaati();
        calismaSaati.setTenant(tenant);
        calismaSaati.setPersonel(personel);
        calismaSaati.setDayOfWeek(request.dayOfWeek());
        calismaSaati.setStartTime(request.startTime());
        calismaSaati.setEndTime(request.endTime());

        calismaSaatiRepository.save(calismaSaati);

        return toResponse(calismaSaati);
    }

    @Transactional(readOnly = true)
    public List<CalismaSaatiDtos.CalismaSaatiResponse> listele(UUID personelId) {
        return calismaSaatiRepository.findByPersonelId(personelId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private CalismaSaatiDtos.CalismaSaatiResponse toResponse(CalismaSaati c) {
        return new CalismaSaatiDtos.CalismaSaatiResponse(
                c.getId(),
                c.getPersonel().getId(),
                c.getDayOfWeek(),
                c.getStartTime(),
                c.getEndTime()
        );
    }
}