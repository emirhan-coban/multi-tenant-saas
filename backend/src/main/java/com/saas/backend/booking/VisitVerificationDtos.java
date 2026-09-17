package com.saas.backend.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public final class VisitVerificationDtos {
    public record Code(UUID appointmentId, String code, Instant expiresAt) {}
    public record Verify(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {}
    public record Status(UUID appointmentId, boolean verified, Instant verifiedAt) {}
    public record Attempt(boolean success, Status status, String errorCode, String message) {}

    private VisitVerificationDtos() {}
}
