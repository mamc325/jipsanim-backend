package com.jipsanim.outbox.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 도메인 서비스가 자신의 @Transactional 안에서 호출해 OutboxEvent 를 적재한다(원칙 IV).
 * event_key 로 producer 멱등(중복 적재 no-op, 리뷰 P0-2/P1).
 */
@Component
public class OutboxEventPublisher {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 호출자(도메인) 트랜잭션에 참여해 적재. eventKey 중복이면 조용히 no-op.
     * payload 는 최소한 recipientUserId 를 포함해야 한다(Dispatcher 가 사용).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(String aggregateType, Long aggregateId, String eventType, String eventKey, Object payload) {
        String json = serialize(payload);
        repository.appendIgnoringDuplicate(aggregateType, aggregateId, eventType, eventKey, json,
                LocalDateTime.now(clock));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload 직렬화 실패: " + eventKeyHint(payload), e);
        }
    }

    private String eventKeyHint(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}
