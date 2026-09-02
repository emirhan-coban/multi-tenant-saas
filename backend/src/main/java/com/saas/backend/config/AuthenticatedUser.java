package com.saas.backend.config;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID tenantId, String role) {}