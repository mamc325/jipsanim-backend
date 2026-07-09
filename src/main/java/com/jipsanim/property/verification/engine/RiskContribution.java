package com.jipsanim.property.verification.engine;

/**
 * 각 검증 사유가 위험도에 기여하는 정도.
 * REVIEW_REQUIRED 는 "자동 판정 불가 → 관리자 검토" 신호 (가격 기준 부재/부족).
 */
public enum RiskContribution {
    LOW,
    MEDIUM,
    HIGH,
    REVIEW_REQUIRED
}
