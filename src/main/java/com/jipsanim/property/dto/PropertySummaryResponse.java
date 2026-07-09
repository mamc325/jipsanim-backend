package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;

import java.math.BigDecimal;

public record PropertySummaryResponse(
        Long propertyId,
        String title,
        String regionName,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        String primaryImageUrl) {
}
