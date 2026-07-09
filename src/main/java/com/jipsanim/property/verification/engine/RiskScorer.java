package com.jipsanim.property.verification.engine;

import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 사유 목록으로 riskLevel 과 검증 상태를 산정.
 * - REVIEW_REQUIRED 기여가 있으면 상태는 REVIEW_REQUIRED (자동 HIGH 금지, Constitution III).
 * - riskLevel 은 기여 중 최고치 (REVIEW_REQUIRED 는 MEDIUM 으로 취급).
 */
@Component
public class RiskScorer {

    public RiskAssessment assess(List<VerificationFinding> findings) {
        boolean review = findings.stream()
                .anyMatch(f -> f.contribution() == RiskContribution.REVIEW_REQUIRED);
        RiskLevel risk = RiskLevel.LOW;
        for (VerificationFinding finding : findings) {
            RiskLevel contributed = switch (finding.contribution()) {
                case HIGH -> RiskLevel.HIGH;
                case MEDIUM, REVIEW_REQUIRED -> RiskLevel.MEDIUM;
                case LOW -> RiskLevel.LOW;
            };
            if (contributed.ordinal() > risk.ordinal()) {
                risk = contributed;
            }
        }
        VerificationStatus status = review ? VerificationStatus.REVIEW_REQUIRED : VerificationStatus.PENDING;
        return new RiskAssessment(risk, status);
    }

    public record RiskAssessment(RiskLevel riskLevel, VerificationStatus status) {
    }
}
