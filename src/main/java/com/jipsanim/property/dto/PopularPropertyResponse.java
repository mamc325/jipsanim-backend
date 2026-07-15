package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;

import java.math.BigDecimal;

/**
 * 인기 매물 목록 응답(6차). 요약 필드 + viewCount(생애 누적). 검색이 공유하는
 * {@link PropertySummaryResponse} 와 분리(불변 유지, 리뷰 P2).
 */
public record PopularPropertyResponse(
        Long propertyId,
        String title,
        String regionName,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        String primaryImageUrl,
        long viewCount) {
}
