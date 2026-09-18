package com.saas.backend.auth;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.customer.CustomerPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final SessionRevocationService sessions;

    public SessionController(SessionRevocationService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/auth/logout-all")
    public ResponseEntity<Void> staff(@AuthenticationPrincipal AuthenticatedUser user) {
        sessions.revokeStaff(user.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/customer/v1/auth/logout-all")
    public ResponseEntity<Void> customer(@AuthenticationPrincipal CustomerPrincipal customer) {
        sessions.revokeCustomer(customer.customerId());
        return ResponseEntity.noContent().build();
    }
}
