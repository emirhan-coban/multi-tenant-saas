package com.saas.backend.izinkaydi;

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
@RequestMapping("/api/time-off")
public class IzinKaydiController {

    private final IzinKaydiService izinKaydiService;

    public IzinKaydiController(IzinKaydiService izinKaydiService) {
        this.izinKaydiService = izinKaydiService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<IzinKaydiDtos.IzinKaydiResponse> olustur(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody IzinKaydiDtos.IzinKaydiRequest request
    ) {
        var response = izinKaydiService.olustur(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<IzinKaydiDtos.IzinKaydiResponse> listele(@RequestParam UUID personelId) {
        return izinKaydiService.listele(personelId);
    }
}