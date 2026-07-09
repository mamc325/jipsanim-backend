package com.jipsanim.property.verification.engine;

import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.verification.engine.rule.PriceOutOfRangeRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PriceOutOfRangeRuleTest {

    private final PriceOutOfRangeRule rule = new PriceOutOfRangeRule();

    private Property property(long deposit, long monthlyRent) {
        return Property.createDraft(null, "역삼 오피스텔", "설명설명설명설명설명설명설명설명설명설명",
                "서울 강남구 테헤란로 101", "1168010100", "강남구", "강남역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, deposit, monthlyRent, new BigDecimal("33"), 1);
    }

    private PriceStandard standard(DataStatus dataStatus) {
        return PriceStandard.activate("11680", "강남구", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                5_000_000L, 50_000_000L, 550_000L, 1_800_000L, 100, dataStatus, "MOLIT_OFFICETEL_RENT");
    }

    @Test
    @DisplayName("기준이 없으면 HIGH 대신 REVIEW_REQUIRED(INSUFFICIENT_STANDARD)")
    void noStandard() {
        var ctx = new VerificationContext(property(10_000_000L, 700_000L), Optional.empty(), false);

        Optional<VerificationFinding> finding = rule.evaluate(ctx);

        assertThat(finding).isPresent();
        assertThat(finding.get().reasonType()).isEqualTo(ReasonType.INSUFFICIENT_STANDARD);
        assertThat(finding.get().contribution()).isEqualTo(RiskContribution.REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("기준 표본 부족(INSUFFICIENT_DATA)도 REVIEW_REQUIRED")
    void insufficientStandard() {
        var ctx = new VerificationContext(property(10_000_000L, 700_000L),
                Optional.of(standard(DataStatus.INSUFFICIENT_DATA)), false);

        Optional<VerificationFinding> finding = rule.evaluate(ctx);

        assertThat(finding).map(VerificationFinding::contribution).contains(RiskContribution.REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("월세가 기준 하한 미만이면 PRICE_OUT_OF_RANGE(HIGH)")
    void monthlyRentTooLow() {
        var ctx = new VerificationContext(property(10_000_000L, 200_000L), // 월세 20만 < 하한 55만
                Optional.of(standard(DataStatus.SUFFICIENT)), false);

        Optional<VerificationFinding> finding = rule.evaluate(ctx);

        assertThat(finding).isPresent();
        assertThat(finding.get().reasonType()).isEqualTo(ReasonType.PRICE_OUT_OF_RANGE);
        assertThat(finding.get().contribution()).isEqualTo(RiskContribution.HIGH);
    }

    @Test
    @DisplayName("범위 안이면 사유 없음")
    void inRange() {
        var ctx = new VerificationContext(property(10_000_000L, 700_000L),
                Optional.of(standard(DataStatus.SUFFICIENT)), false);

        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
