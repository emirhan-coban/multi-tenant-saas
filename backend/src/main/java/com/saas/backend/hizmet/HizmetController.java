package com.saas.backend.hizmet;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/services")
public class HizmetController {

    private final HizmetService hizmetService;

    public HizmetController(HizmetService hizmetService) {
        this.hizmetService = hizmetService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<HizmetDtos.HizmetResponse> olustur(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody HizmetDtos.HizmetOlusturRequest request
    ) {
        var response = hizmetService.olustur(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<HizmetDtos.HizmetResponse> listele() {
        return hizmetService.listele();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public HizmetDtos.HizmetResponse guncelle(
        @PathVariable UUID id,
        @Valid @RequestBody HizmetDtos.HizmetGuncelleRequest request
    ) {
        return hizmetService.guncelle(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Void> sil(@PathVariable UUID id) {
        hizmetService.sil(id);
        return ResponseEntity.noContent().build();
    }
}