package com.saas.backend.hizmet;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public class HizmetDtos {

    public record HizmetOlusturRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(1) @Max(1440) Integer durationMin,
        @NotNull @DecimalMin("0.0") @DecimalMax("1000000") BigDecimal price,
        @Min(0) @Max(480) Integer bufferMin
    ) {}

    public record HizmetGuncelleRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(1) @Max(1440) Integer durationMin,
        @NotNull @DecimalMin("0.0") @DecimalMax("1000000") BigDecimal price,
        @Min(0) @Max(480) Integer bufferMin
    ) {}

    public record HizmetResponse(
        UUID id,
        String name,
        Integer durationMin,
        BigDecimal price,
        Integer bufferMin
    ) {}
}