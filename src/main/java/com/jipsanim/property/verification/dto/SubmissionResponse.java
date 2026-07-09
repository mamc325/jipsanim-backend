package com.jipsanim.property.verification.dto;

import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.verification.engine.VerificationResult;

import java.util.List;

public record SubmissionResponse(
        Long propertyId,
        PropertyStatus status,
        VerificationStatus verificationStatus,
        RiskLevel riskLevel,
        List<ReasonItem> reasons) {

    public record ReasonItem(ReasonType reasonType, String message) {
    }

    public static SubmissionResponse of(Property property, VerificationResult result) {
        List<ReasonItem> reasons = result.findings().stream()
                .map(f -> new ReasonItem(f.reasonType(), f.message()))
                .toList();
        return new SubmissionResponse(property.getId(), property.getStatus(),
                result.status(), result.riskLevel(), reasons);
    }
}
