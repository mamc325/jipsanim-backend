package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record PropertyCreateRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        @NotBlank String roadAddress,
        @NotBlank @Size(min = 5, max = 10) String bjdongCode,
        String regionName,
        String nearStation,
        @NotNull PropertyType propertyType,
        @NotNull DealType dealType,
        @NotNull @PositiveOrZero Long deposit,
        @PositiveOrZero Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        @Valid List<ImageRequest> images) {

    public record ImageRequest(@NotBlank String imageUrl, boolean isPrimary) {
    }
}
