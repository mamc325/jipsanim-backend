package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 중개사 본인 매물 목록(DRAFT/PENDING/REJECTED/ACTIVE 등 전 상태). 공개 검색(ACTIVE only)과 달리
 * realtorId 로 필터하고 상태를 그대로 노출. DTO projection 으로 조회.
 */
public record MyPropertyResponse(
        Long propertyId,
        String title,
        String regionName,
        PropertyStatus status,
        VerificationStatus verificationStatus,
        RiskLevel riskLevel,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        LocalDateTime createdAt) {
}
