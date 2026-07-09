package com.jipsanim.property.verification.engine.rule;

import com.jipsanim.property.domain.PropertyImage;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.RiskContribution;
import com.jipsanim.property.verification.engine.VerificationContext;
import com.jipsanim.property.verification.engine.VerificationFinding;
import com.jipsanim.property.verification.engine.VerificationRule;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MissingImageRule implements VerificationRule {

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        boolean hasPrimary = context.property().getImages().stream().anyMatch(PropertyImage::isPrimary);
        if (!hasPrimary) {
            return Optional.of(new VerificationFinding(ReasonType.MISSING_IMAGE,
                    "대표 이미지가 없습니다.", RiskContribution.MEDIUM));
        }
        return Optional.empty();
    }
}
