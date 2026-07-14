package com.jipsanim.outbox.dto;

import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;

import java.time.LocalDateTime;

public record OutboxEventResponse(
        Long outboxEventId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        OutboxStatus status,
        int attempts,
        LocalDateTime nextRetryAt,
        String lastError,
        LocalDateTime publishedAt,
        LocalDateTime createdAt
) {
    public static OutboxEventResponse from(OutboxEvent e) {
        return new OutboxEventResponse(e.getId(), e.getAggregateType(), e.getAggregateId(),
                e.getEventType(), e.getStatus(), e.getAttempts(), e.getNextRetryAt(),
                e.getLastError(), e.getPublishedAt(), e.getCreatedAt());
    }
}
