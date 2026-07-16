package com.jipsanim.pricestandard.dto;

import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandard;

/**
 * 매물 상세/검증 화면용 시세 기준 요약(프론트: "실거래가 기준 vs 등록가" 비교·시세대비 계산).
 * (sigungu, propertyType, dealType) 당 ACTIVE 1건 기준. 없으면 null 로 전달.
 */
public record PriceStandardSummary(
        Long minDeposit,
        Long maxDeposit,
        Long minMonthlyRent,
        Long maxMonthlyRent,
        int sampleCount,
        DataStatus dataStatus) {

    public static PriceStandardSummary from(PriceStandard ps) {
        if (ps == null) {
            return null;
        }
        return new PriceStandardSummary(ps.getMinDeposit(), ps.getMaxDeposit(),
                ps.getMinMonthlyRent(), ps.getMaxMonthlyRent(), ps.getSampleCount(), ps.getDataStatus());
    }
}
