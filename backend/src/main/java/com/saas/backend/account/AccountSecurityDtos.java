package com.saas.backend.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AccountSecurityDtos {
    private AccountSecurityDtos() {
    }

    public record ForgotPassword(@NotBlank @Email @Size(max = 255) String email) {
    }

    public record ResetPassword(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }

    public record VerifyEmail(@NotBlank @Size(max = 200) String token) {
    }

    public record EmailStatus(boolean emailVerified) {
    }
}
