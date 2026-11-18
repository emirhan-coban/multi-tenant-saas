package com.saas.backend.personel;

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
@RequestMapping("/api/staff")
public class PersonelController {

    private final PersonelService personelService;

    public PersonelController(PersonelService personelService) {
        this.personelService = personelService;
    }

    @PostMapping("/invite")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<PersonelDtos.DavetSonucu> davetEt(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PersonelDtos.DavetEtRequest request
    ) {
        var response = personelService.davetEt(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
    public List<PersonelDtos.PersonelResponse> listele(@AuthenticationPrincipal AuthenticatedUser user) {
        return personelService.listele(user);
    }

    @PostMapping("/self")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PersonelDtos.PersonelResponse kendiniEkle(@AuthenticationPrincipal AuthenticatedUser user) {
        return personelService.kendiniEkle(user);
    }

    @GetMapping("/invitations")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<PersonelDtos.BekleyenDavet> bekleyenDavetler(@AuthenticationPrincipal AuthenticatedUser user) {
        return personelService.bekleyenDavetler(user);
    }

    @DeleteMapping("/{personelId}/access")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Void> erisimiKaldir(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable UUID personelId) {
        personelService.erisimiKaldir(user, personelId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<Void> davetiKabulEt(@Valid @RequestBody PersonelDtos.DavetKabulRequest request) {
        personelService.davetiKabulEt(request);
        return ResponseEntity.noContent().build();
    }
}
