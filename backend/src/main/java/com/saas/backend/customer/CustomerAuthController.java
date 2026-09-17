package com.saas.backend.customer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/v1")
public class CustomerAuthController {
    private final CustomerAuthService service;

    public CustomerAuthController(CustomerAuthService service) {
        this.service = service;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<CustomerAuthDtos.Response> register(@Valid @RequestBody CustomerAuthDtos.Register request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PostMapping("/auth/login")
    public CustomerAuthDtos.Response login(@Valid @RequestBody CustomerAuthDtos.Login request) {
        return service.login(request);
    }

    @PostMapping("/auth/refresh")
    public CustomerAuthDtos.Response refresh(@Valid @RequestBody CustomerAuthDtos.Refresh request) {
        return service.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    public CustomerAuthDtos.Customer me(@AuthenticationPrincipal CustomerPrincipal principal) {
        return service.me(principal.customerId());
    }
}
