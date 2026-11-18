package com.saas.backend.auth;

import com.saas.backend.config.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    public record SwitchTenantRequest(@jakarta.validation.constraints.NotNull java.util.UUID tenantId) {}

    @org.springframework.web.bind.annotation.PostMapping("/api/me/switch-tenant")
    public AuthResponse switchTenant(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody SwitchTenantRequest request) {
        var user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return authService.switchTenant(user.userId(), request.tenantId());
    }

    @GetMapping("/api/me")
    public AuthenticatedUser me() {
        return (AuthenticatedUser) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}