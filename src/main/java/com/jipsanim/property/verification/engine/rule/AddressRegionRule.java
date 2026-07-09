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

/** 주소(법정동코드)와 시군구코드 정합성 — bjdongCode 앞 5자리가 sigunguCode 와 일치해야 한다. */
@Component
public class AddressRegionRule implements VerificationRule {

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        Property p = context.property();
        String bjdong = p.getBjdongCode();
        String sigungu = p.getSigunguCode();
        boolean mismatch = !StringUtils.hasText(bjdong) || !StringUtils.hasText(sigungu)
                || bjdong.length() < 5 || !bjdong.startsWith(sigungu);
        if (mismatch) {
            return Optional.of(new VerificationFinding(ReasonType.ADDRESS_REGION_MISMATCH,
                    "주소(법정동코드)와 지역코드가 일치하지 않습니다.", RiskContribution.HIGH));
        }
        return Optional.empty();
    }
}
