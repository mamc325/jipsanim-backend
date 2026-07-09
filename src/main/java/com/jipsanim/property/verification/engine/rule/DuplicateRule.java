package com.jipsanim.property.verification.engine.rule;

import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.RiskContribution;
import com.jipsanim.property.verification.engine.VerificationContext;
import com.jipsanim.property.verification.engine.VerificationFinding;
import com.jipsanim.property.verification.engine.VerificationRule;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DuplicateRule implements VerificationRule {

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        if (context.duplicateSuspected()) {
            return Optional.of(new VerificationFinding(ReasonType.DUPLICATE_SUSPECTED,
                    "동일 주소·거래유형의 매물이 이미 존재합니다.", RiskContribution.MEDIUM));
        }
        return Optional.empty();
    }
}
