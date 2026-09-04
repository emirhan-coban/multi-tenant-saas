package com.saas.backend.izinkaydi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public class IzinKaydiDtos {

    public record IzinKaydiRequest(
            @NotNull UUID personelId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            String reason
    ) {}

    public record IzinKaydiResponse(
            UUID id,
            UUID personelId,
            Instant startsAt,
            Instant endsAt,
            String reason
    ) {}
}