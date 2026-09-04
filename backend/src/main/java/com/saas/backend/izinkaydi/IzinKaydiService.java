package com.saas.backend.izinkaydi;

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
public class IzinKaydiService {

    private final IzinKaydiRepository izinKaydiRepository;
    private final PersonelRepository personelRepository;
    private final TenantRepository tenantRepository;

    public IzinKaydiService(
            IzinKaydiRepository izinKaydiRepository,
            PersonelRepository personelRepository,
            TenantRepository tenantRepository
    ) {
        this.izinKaydiRepository = izinKaydiRepository;
        this.personelRepository = personelRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public IzinKaydiDtos.IzinKaydiResponse olustur(AuthenticatedUser user, IzinKaydiDtos.IzinKaydiRequest request) {
        if (!request.startsAt().isBefore(request.endsAt())) {
            throw new ApiException("INVALID_RANGE", "Başlangıç tarihi bitiş tarihinden önce olmalı");
        }

        Tenant tenant = tenantRepository.getReferenceById(user.tenantId());
        Personel personel = personelRepository.findById(request.personelId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Personel bulunamadı"));

        IzinKaydi izinKaydi = new IzinKaydi();
        izinKaydi.setTenant(tenant);
        izinKaydi.setPersonel(personel);
        izinKaydi.setStartsAt(request.startsAt());
        izinKaydi.setEndsAt(request.endsAt());
        izinKaydi.setReason(request.reason());

        izinKaydiRepository.save(izinKaydi);

        return toResponse(izinKaydi);
    }

    @Transactional(readOnly = true)
    public List<IzinKaydiDtos.IzinKaydiResponse> listele(UUID personelId) {
        return izinKaydiRepository.findByPersonelId(personelId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private IzinKaydiDtos.IzinKaydiResponse toResponse(IzinKaydi i) {
        return new IzinKaydiDtos.IzinKaydiResponse(
                i.getId(),
                i.getPersonel().getId(),
                i.getStartsAt(),
                i.getEndsAt(),
                i.getReason()
        );
    }
}