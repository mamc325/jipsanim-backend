package com.jipsanim.outbox.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outbox 이벤트. 도메인 상태 변경과 동일 커밋에 적재되고 폴링 Worker 가 발행한다(원칙 IV).
 * event_key UNIQUE 로 producer 멱등(같은 사건 1건), 상태전이는 spec §3.
 */
@Entity
@Table(name = "outbox_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_key", columnNames = "event_key"),
        indexes = {
                @Index(name = "idx_outbox_poll", columnList = "status, next_retry_at"),
                @Index(name = "idx_outbox_reaper", columnList = "status, processing_started_at")
        })
public class OutboxEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_key", nullable = false, length = 120)
    private String eventKey;

    @Column(columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(String aggregateType, Long aggregateId, String eventType,
                        String eventKey, String payload, LocalDateTime now) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventKey = eventKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextRetryAt = now;
    }

    public static OutboxEvent create(String aggregateType, Long aggregateId, String eventType,
                                     String eventKey, String payload, LocalDateTime now) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, eventKey, payload, now);
    }

    /** Worker 선점: PENDING → PROCESSING (processing_started_at 기록). */
    public void markProcessing(LocalDateTime now) {
        this.status = OutboxStatus.PROCESSING;
        this.processingStartedAt = now;
    }

    /** 발행 성공: → PUBLISHED. */
    public void markPublished(LocalDateTime now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /** 발행 실패: attempts++ 후 백오프 재시도(PENDING) 또는 소진 시 DEAD. */
    public void markFailed(LocalDateTime now, String error) {
        this.attempts += 1;
        this.lastError = truncate(error);
        this.processingStartedAt = null;
        if (RetryPolicy.isExhausted(this.attempts)) {
            this.status = OutboxStatus.DEAD;
        } else {
            this.status = OutboxStatus.PENDING;
            Duration backoff = RetryPolicy.backoff(this.attempts);
            this.nextRetryAt = now.plus(backoff);
        }
    }

    /** 관리자 재처리: DEAD → PENDING (attempts=0, 즉시 재발행 대상). */
    public void reset(LocalDateTime now) {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextRetryAt = now;
        this.processingStartedAt = null;
        this.lastError = null;
    }

    public boolean isDead() {
        return status == OutboxStatus.DEAD;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public LocalDateTime getProcessingStartedAt() {
        return processingStartedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }
}
