package com.jipsanim.pricestandard.batch;

import com.jipsanim.external.molit.OfficetelRentTransaction;
import com.jipsanim.pricestandard.domain.BatchStatus;

import java.util.List;

/**
 * 배치 수집 결과. transactions 는 후속(Phase 4) 후보 생성에 사용된다.
 */
public record BatchCollectResult(
        Long batchJobId,
        BatchStatus status,
        int totalRequestCount,
        int successCount,
        int failCount,
        List<OfficetelRentTransaction> transactions) {
}
