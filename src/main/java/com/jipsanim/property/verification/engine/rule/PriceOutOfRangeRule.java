package com.jipsanim.property.verification.engine.rule;

import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.RiskContribution;
import com.jipsanim.property.verification.engine.VerificationContext;
import com.jipsanim.property.verification.engine.VerificationFinding;
import com.jipsanim.property.verification.engine.VerificationRule;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 가격 이상치 검증 (FR-041, 042 / Constitution III).
 * - 해당 지역 ACTIVE 기준이 없거나 표본 부족(INSUFFICIENT) → HIGH 판정 금지, REVIEW_REQUIRED.
 * - 기준 범위를 벗어나면 PRICE_OUT_OF_RANGE(HIGH).
 */
@Component
public class PriceOutOfRangeRule implements VerificationRule {

    @Override
    public Optional<VerificationFinding> evaluate(VerificationContext context) {
        Optional<PriceStandard> maybeStandard = context.activeStandard();
        if (maybeStandard.isEmpty()) {
            return Optional.of(new VerificationFinding(ReasonType.INSUFFICIENT_STANDARD,
                    "해당 지역의 시세 기준이 없어 자동 판정할 수 없습니다.", RiskContribution.REVIEW_REQUIRED));
        }
        PriceStandard standard = maybeStandard.get();
        if (standard.isInsufficient()) {
            return Optional.of(new VerificationFinding(ReasonType.INSUFFICIENT_STANDARD,
                    "시세 기준의 표본이 부족해 자동 판정할 수 없습니다.", RiskContribution.REVIEW_REQUIRED));
        }

        Property p = context.property();
        if (outOfRange(p.getDeposit(), standard.getMinDeposit(), standard.getMaxDeposit())) {
            return Optional.of(new VerificationFinding(ReasonType.PRICE_OUT_OF_RANGE,
                    "보증금이 시세 기준 범위를 벗어났습니다.", RiskContribution.HIGH));
        }
        if (p.getDealType() == DealType.MONTHLY_RENT
                && outOfRange(p.getMonthlyRent(), standard.getMinMonthlyRent(), standard.getMaxMonthlyRent())) {
            return Optional.of(new VerificationFinding(ReasonType.PRICE_OUT_OF_RANGE,
                    "월세가 시세 기준 범위를 벗어났습니다.", RiskContribution.HIGH));
        }
        return Optional.empty();
    }

    private boolean outOfRange(Long value, Long min, Long max) {
        if (value == null || min == null || max == null) {
            return false;
        }
        return value < min || value > max;
    }
}
