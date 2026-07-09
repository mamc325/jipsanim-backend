package com.jipsanim.property.verification.engine;

import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScorerTest {

    private final RiskScorer scorer = new RiskScorer();

    private VerificationFinding finding(RiskContribution c) {
        return new VerificationFinding(ReasonType.PRICE_OUT_OF_RANGE, "msg", c);
    }

    @Test
    @DisplayName("사유 없으면 LOW + PENDING")
    void clean() {
        var result = scorer.assess(List.of());
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.status()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    @DisplayName("HIGH 기여가 있으면 riskLevel HIGH, 상태 PENDING")
    void high() {
        var result = scorer.assess(List.of(finding(RiskContribution.LOW), finding(RiskContribution.HIGH)));
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.status()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    @DisplayName("REVIEW_REQUIRED 기여가 있으면 상태 REVIEW_REQUIRED, riskLevel MEDIUM")
    void review() {
        var result = scorer.assess(List.of(finding(RiskContribution.REVIEW_REQUIRED)));
        assertThat(result.status()).isEqualTo(VerificationStatus.REVIEW_REQUIRED);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }
}
