package com.jipsanim.property.verification.engine;

import com.jipsanim.property.domain.ReasonType;

public record VerificationFinding(ReasonType reasonType, String message, RiskContribution contribution) {
}
