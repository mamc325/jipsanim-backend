package com.jipsanim.pricestandard.candidate.dto;

import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;

import java.time.LocalDateTime;

public record PriceStandardResponse(
        Long id,
        String sigunguCode,
        String regionName,
        PropertyType propertyType,
        DealType dealType,
        Long minDeposit,
        Long maxDeposit,
        Long minMonthlyRent,
        Long maxMonthlyRent,
        int sampleCount,
        DataStatus dataStatus,
        String source,
        PriceStandardStatus status,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo) {

    public static PriceStandardResponse from(PriceStandard p) {
        return new PriceStandardResponse(p.getId(), p.getSigunguCode(), p.getRegionName(), p.getPropertyType(),
                p.getDealType(), p.getMinDeposit(), p.getMaxDeposit(), p.getMinMonthlyRent(), p.getMaxMonthlyRent(),
                p.getSampleCount(), p.getDataStatus(), p.getSource(), p.getStatus(),
                p.getEffectiveFrom(), p.getEffectiveTo());
    }
}
