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
            boolean invitationSent,
            /** Yalnızca e-posta gerçek gönderimde değilken (demo/test) doludur; sahip bağlantıyı elle iletir. */
            String inviteLink
    ) {}

    public record DavetKabulRequest(
            @NotBlank String token,
            @Size(min = 8, max = 72) String password
    ) {}

    public record PersonelResponse(
            UUID id,
            String displayName,
            boolean active,
            /** Giriş yapmış kullanıcının kendi personel kaydı mı (sahip "ben de berberim" demiş mi). */
            boolean self
    ) {}

    public record BekleyenDavet(
            UUID id,
            String email,
            String displayName,
            java.time.Instant createdAt,
            java.time.Instant expiresAt,
            boolean expired
    ) {}
}
