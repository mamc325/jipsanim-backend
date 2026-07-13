package com.jipsanim.settlement.domain;

public enum SettlementStatus {
    PENDING,   // 배치 생성 직후
    CONFIRMED, // 관리자 확정
    PAID       // 지급 완료
}
