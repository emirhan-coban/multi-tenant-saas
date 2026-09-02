package com.saas.backend.auth;

import com.saas.backend.config.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/api/me")
    public AuthenticatedUser me() {
        return (AuthenticatedUser) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}