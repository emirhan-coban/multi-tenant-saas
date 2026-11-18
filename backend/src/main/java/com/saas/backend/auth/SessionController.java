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
    private final com.saas.backend.operations.OperationsService operations;

    public SessionController(SessionRevocationService sessions, com.saas.backend.operations.OperationsService operations) {
        this.sessions = sessions;
        this.operations = operations;
    }

    @PostMapping("/api/auth/logout-all")
    public ResponseEntity<Void> staff(@AuthenticationPrincipal AuthenticatedUser user) {
        sessions.revokeStaff(user.userId());
        operations.audit(user, "SESSIONS_REVOKED", "user", user.userId(), java.util.Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/customer/v1/auth/logout-all")
    public ResponseEntity<Void> customer(@AuthenticationPrincipal CustomerPrincipal customer) {
        sessions.revokeCustomer(customer.customerId());
        return ResponseEntity.noContent().build();
    }
}
