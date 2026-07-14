package com.jipsanim.outbox.domain;

public enum OutboxStatus {
    PENDING,     // 발행 대기(폴링 대상)
    PROCESSING,  // Worker 선점 중
    PUBLISHED,   // 발행 완료
    DEAD         // 재시도 소진(수동 재처리 대상)
}
