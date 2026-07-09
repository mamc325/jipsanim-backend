package com.jipsanim.external.log;

import java.time.LocalDateTime;

public record ExternalApiCallLogResponse(
        Long id,
        ApiType apiType,
        String requestUrl,
        String requestParams,
        Integer responseStatus,
        boolean success,
        String errorMessage,
        Integer elapsedTimeMs,
        Long batchJobId,
        LocalDateTime calledAt) {

    public static ExternalApiCallLogResponse from(ExternalApiCallLog log) {
        return new ExternalApiCallLogResponse(log.getId(), log.getApiType(), log.getRequestUrl(),
                log.getRequestParams(), log.getResponseStatus(), log.isSuccess(), log.getErrorMessage(),
                log.getElapsedTimeMs(), log.getBatchJobId(), log.getCalledAt());
    }
}
