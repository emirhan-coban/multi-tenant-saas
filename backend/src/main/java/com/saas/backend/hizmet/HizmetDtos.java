package com.saas.backend.hizmet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public class HizmetDtos {

    public record HizmetOlusturRequest(
        @NotBlank String name,
        @NotNull @Min(1) Integer durationMin,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        Integer bufferMin
    ) {}

    public record HizmetGuncelleRequest(
        @NotBlank String name,
        @NotNull @Min(1) Integer durationMin,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        Integer bufferMin
    ) {}

    public record HizmetResponse(
        UUID id,
        String name,
        Integer durationMin,
        BigDecimal price,
        Integer bufferMin
    ) {}
}