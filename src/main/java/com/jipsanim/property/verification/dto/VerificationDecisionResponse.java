package com.jipsanim.property.verification.dto;

import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.domain.VerificationStatus;

public record VerificationDecisionResponse(
        Long verificationId,
        Long propertyId,
        PropertyStatus propertyStatus,
        VerificationStatus verificationStatus) {
}
