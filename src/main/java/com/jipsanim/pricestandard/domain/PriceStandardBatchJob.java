package com.jipsanim.pricestandard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "price_standard_batch_job")
public class PriceStandardBatchJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_month", length = 7)
    private String jobMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "total_request_count")
    private int totalRequestCount;

    @Column(name = "success_count")
    private int successCount;

    @Column(name = "fail_count")
    private int failCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "triggered_by", length = 20)
    private String triggeredBy;

    protected PriceStandardBatchJob() {
    }

    private PriceStandardBatchJob(String jobMonth, String triggeredBy) {
        this.jobMonth = jobMonth;
        this.triggeredBy = triggeredBy;
        this.status = BatchStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public static PriceStandardBatchJob start(String jobMonth, String triggeredBy) {
        return new PriceStandardBatchJob(jobMonth, triggeredBy);
    }

    public void complete(int total, int success, int fail) {
        this.totalRequestCount = total;
        this.successCount = success;
        this.failCount = fail;
        this.finishedAt = LocalDateTime.now();
        if (fail == 0) {
            this.status = BatchStatus.SUCCESS;
        } else if (success == 0) {
            this.status = BatchStatus.FAILED;
        } else {
            this.status = BatchStatus.PARTIAL_FAILED;
        }
    }

    public void fail(String errorMessage) {
        this.status = BatchStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getJobMonth() {
        return jobMonth;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public int getTotalRequestCount() {
        return totalRequestCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }
}
