package com.saas.backend.personel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public class PersonelDtos {

    public record DavetEtRequest(
            @NotBlank @Email String email,
            @NotBlank String displayName
    ) {}

    public record DavetSonucu(
            UUID personelId,
            String email,
            String geciciSifre
    ) {}

    public record PersonelResponse(
            UUID id,
            String displayName,
            boolean active
    ) {}
}