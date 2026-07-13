package com.jipsanim.settlement.dto;

/** 정산 배치 실행 결과(동기 200). */
public record SettlementBatchResult(
        String month,
        int createdCount,
        int updatedCount,
        int skippedCount
) {
}
