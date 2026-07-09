package com.jipsanim.pricestandard.candidate.dto;

import com.jipsanim.pricestandard.domain.CalcMethod;
import com.jipsanim.pricestandard.domain.CandidateStatus;
import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;

public record CandidateResponse(
        Long candidateId,
        String sigunguCode,
        String regionName,
        PropertyType propertyType,
        DealType dealType,
        CalcMethod calcMethod,
        Long calcMinDeposit,
        Long calcMaxDeposit,
        Long calcMinMonthlyRent,
        Long calcMaxMonthlyRent,
        int sampleCount,
        DataStatus dataStatus,
        String calculatedMonth,
        CandidateStatus status) {

    public static CandidateResponse from(PriceStandardCandidate c) {
        return new CandidateResponse(c.getId(), c.getSigunguCode(), c.getRegionName(), c.getPropertyType(),
                c.getDealType(), c.getCalcMethod(), c.getCalcMinDeposit(), c.getCalcMaxDeposit(),
                c.getCalcMinMonthlyRent(), c.getCalcMaxMonthlyRent(), c.getSampleCount(), c.getDataStatus(),
                c.getCalculatedMonth(), c.getStatus());
    }
}
