package com.saas.backend.personel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public class PersonelDtos {

    public record DavetEtRequest(
            @NotBlank @Email String email,
            @NotBlank String displayName
    ) {}

    public record DavetSonucu(
            UUID personelId,
            String email,
            boolean invitationSent
    ) {}

    public record DavetKabulRequest(
            @NotBlank String token,
            @Size(min = 8, max = 72) String password
    ) {}

    public record PersonelResponse(
            UUID id,
            String displayName,
            boolean active
    ) {}
}
