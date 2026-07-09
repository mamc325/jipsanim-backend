package com.jipsanim.property.verification.dto;

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
        LocalDateTime requestedAt) {

    public static VerificationSummaryResponse from(PropertyVerification v) {
        List<ReasonType> reasons = v.getReasons().stream()
                .map(PropertyVerificationReason::getReasonType)
                .toList();
        return new VerificationSummaryResponse(v.getId(), v.getPropertyId(), v.getStatus(),
                v.getRiskLevel(), reasons, v.getCreatedAt());
    }
}
