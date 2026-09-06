package com.saas.backend.auth;

import java.util.List;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        List<MembershipInfo> memberships
) {
    public record MembershipInfo(
            UUID tenantId,
            String tenantName,
            String role
    ) {}
}