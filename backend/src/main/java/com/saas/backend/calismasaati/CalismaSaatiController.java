package com.saas.backend.calismasaati;

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
@RequestMapping("/api/working-hours")
public class CalismaSaatiController {

    private final CalismaSaatiService calismaSaatiService;

    public CalismaSaatiController(CalismaSaatiService calismaSaatiService) {
        this.calismaSaatiService = calismaSaatiService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<CalismaSaatiDtos.CalismaSaatiResponse> olustur(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CalismaSaatiDtos.CalismaSaatiRequest request
    ) {
        var response = calismaSaatiService.olustur(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<CalismaSaatiDtos.CalismaSaatiResponse> listele(@RequestParam UUID personelId) {
        return calismaSaatiService.listele(personelId);
    }
}