package com.jipsanim.property.verification.engine.rule;

import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.RiskContribution;
import com.jipsanim.property.verification.engine.VerificationContext;
import com.jipsanim.property.verification.engine.VerificationFinding;
import com.jipsanim.property.verification.engine.VerificationRule;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DescriptionRule implements VerificationRule {

    private static final int MIN_LENGTH = 20;

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        String description = context.property().getDescription();
        if (description == null || description.trim().length() < MIN_LENGTH) {
            return Optional.of(new VerificationFinding(ReasonType.DESCRIPTION_TOO_SHORT,
                    "설명이 너무 짧습니다(최소 %d자).".formatted(MIN_LENGTH), RiskContribution.LOW));
        }
        return Optional.empty();
    }
}
