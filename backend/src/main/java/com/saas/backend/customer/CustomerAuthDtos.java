package com.saas.backend.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class CustomerAuthDtos {
    private CustomerAuthDtos() {
    }

    public record Register(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 200) String fullName,
            @Pattern(regexp = "^$|^[+0-9 ()-]{7,30}$", message = "Telefon biçimi geçersiz") String phone
    ) {
    }

    public record Login(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record Refresh(@NotBlank String refreshToken) {
    }

    public record Customer(UUID id, String email, String fullName, String phone) {
    }

    public record Response(String accessToken, String refreshToken, Customer customer) {
    }
}
