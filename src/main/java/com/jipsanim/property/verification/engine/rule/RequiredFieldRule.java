package com.jipsanim.property.verification.engine.rule;

import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.RiskContribution;
import com.jipsanim.property.verification.engine.VerificationContext;
import com.jipsanim.property.verification.engine.VerificationFinding;
import com.jipsanim.property.verification.engine.VerificationRule;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class RequiredFieldRule implements VerificationRule {

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        Property p = context.property();
        boolean missing = !StringUtils.hasText(p.getTitle())
                || !StringUtils.hasText(p.getRoadAddress())
                || !StringUtils.hasText(p.getBjdongCode())
                || p.getDeposit() == null;
        if (missing) {
            return Optional.of(new VerificationFinding(ReasonType.MISSING_REQUIRED_FIELD,
                    "필수 정보(제목/주소/지역코드/보증금)가 누락되었습니다.", RiskContribution.HIGH));
        }
        return Optional.empty();
    }
}
