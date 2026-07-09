package com.jipsanim.property.verification.engine;

import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;

import java.util.List;

public record VerificationResult(RiskLevel riskLevel, VerificationStatus status, List<VerificationFinding> findings) {
}
