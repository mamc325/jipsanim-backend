package com.jipsanim.external.log;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "external_api_call_log", indexes = {
        @Index(name = "idx_call_log_type_time", columnList = "api_type, called_at"),
        @Index(name = "idx_call_log_batch", columnList = "batch_job_id")
})
public class ExternalApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "api_type", nullable = false, length = 40)
    private ApiType apiType;

    @Column(name = "request_url", length = 1000)
    private String requestUrl;

    @Column(name = "request_params", length = 1000)
    private String requestParams;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "elapsed_time_ms")
    private Integer elapsedTimeMs;

    @Column(name = "batch_job_id")
    private Long batchJobId;

    @Column(name = "called_at", nullable = false)
    private LocalDateTime calledAt;

    protected ExternalApiCallLog() {
    }

    private ExternalApiCallLog(ApiType apiType, String requestUrl, String requestParams,
                              Integer responseStatus, boolean success, String errorMessage,
                              Integer elapsedTimeMs) {
        this.apiType = apiType;
        this.requestUrl = requestUrl;
        this.requestParams = requestParams;
        this.responseStatus = responseStatus;
        this.success = success;
        this.errorMessage = errorMessage;
        this.elapsedTimeMs = elapsedTimeMs;
        this.calledAt = LocalDateTime.now();
    }

    public static ExternalApiCallLog success(ApiType apiType, String requestUrl, String requestParams,
                                             Integer responseStatus, Integer elapsedTimeMs) {
        return new ExternalApiCallLog(apiType, requestUrl, requestParams, responseStatus, true, null, elapsedTimeMs);
    }

    public static ExternalApiCallLog failure(ApiType apiType, String requestUrl, String requestParams,
                                             String errorMessage, Integer elapsedTimeMs) {
        return new ExternalApiCallLog(apiType, requestUrl, requestParams, null, false, errorMessage, elapsedTimeMs);
    }

    public Long getId() {
        return id;
    }

    public boolean isSuccess() {
        return success;
    }

    public ApiType getApiType() {
        return apiType;
    }
}
