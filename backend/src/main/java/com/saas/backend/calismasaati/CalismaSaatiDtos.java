package com.saas.backend.calismasaati;

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

    public record CalismaSaatiResponse(
            UUID id,
            UUID personelId,
            Integer dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {}
}