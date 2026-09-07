package com.saas.backend.calismasaati;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.UUID;

public class CalismaSaatiDtos {

    public record CalismaSaatiRequest(
            @NotNull UUID personelId,
            @NotNull @Min(0) @Max(6) Integer dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime
    ) {}

    // 0 = Monday, 6 = Sunday. Exactly one entry for each day is required.
    public record HaftalikRequest(
            @NotNull UUID personelId,
            @NotNull @Size(min = 7, max = 7) List<@NotNull @Valid GunRequest> days
    ) {}

    public record GunRequest(
            @NotNull @Min(0) @Max(6) Integer dayOfWeek,
            @NotNull Boolean closed,
            LocalTime startTime,
            LocalTime endTime
    ) {}

    public record CalismaSaatiResponse(
            UUID id,
            UUID personelId,
            Integer dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {}
}