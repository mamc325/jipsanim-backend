package com.jipsanim.property.verification.dto;

import com.jipsanim.pricestandard.dto.PriceStandardSummary;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.domain.PropertyVerificationReason;

import java.time.LocalDateTime;
import java.util.List;

public record VerificationSummaryResponse(
        Long verificationId,
        Long propertyId,
        VerificationStatus status,
        RiskLevel riskLevel,
        List<ReasonType> reasons,
        LocalDateTime requestedAt,
        String propertyTitle,
        String regionName,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        PriceStandardSummary priceStandard) {

    public static VerificationSummaryResponse from(PropertyVerification v) {
        return of(v, null, null);
    }

    /** property/priceStandard 는 서비스에서 배치 조회해 병합(N+1 금지). 없으면 null. */
    public static VerificationSummaryResponse of(PropertyVerification v, Property p, PriceStandardSummary ps) {
        List<ReasonType> reasons = v.getReasons().stream()
                .map(PropertyVerificationReason::getReasonType)
                .toList();
        return new VerificationSummaryResponse(v.getId(), v.getPropertyId(), v.getStatus(),
                v.getRiskLevel(), reasons, v.getCreatedAt(),
                p == null ? null : p.getTitle(),
                p == null ? null : p.getRegionName(),
                p == null ? null : p.getDealType(),
                p == null ? null : p.getDeposit(),
                p == null ? null : p.getMonthlyRent(),
                ps);
    }
}
