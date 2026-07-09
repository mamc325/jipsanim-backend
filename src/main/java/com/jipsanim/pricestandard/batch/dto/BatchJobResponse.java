package com.jipsanim.pricestandard.batch.dto;

import com.jipsanim.pricestandard.domain.BatchStatus;
import com.jipsanim.pricestandard.domain.PriceStandardBatchJob;

import java.time.LocalDateTime;

public record BatchJobResponse(
        Long batchJobId,
        String jobMonth,
        BatchStatus status,
        int totalRequestCount,
        int successCount,
        int failCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String triggeredBy) {

    public static BatchJobResponse from(PriceStandardBatchJob job) {
        return new BatchJobResponse(job.getId(), job.getJobMonth(), job.getStatus(),
                job.getTotalRequestCount(), job.getSuccessCount(), job.getFailCount(),
                job.getStartedAt(), job.getFinishedAt(), job.getTriggeredBy());
    }
}
