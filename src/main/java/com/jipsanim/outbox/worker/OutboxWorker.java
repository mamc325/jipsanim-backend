package com.jipsanim.outbox.worker;

import com.jipsanim.notification.dispatch.NotificationDispatcher;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 발행의 트랜잭션 단위(각 메서드가 독립 커밋). 오케스트레이션은 {@link OutboxPoller}.
 * 자기호출 시 프록시 우회로 트랜잭션이 안 걸리므로 단위 메서드는 별 빈(OutboxPoller)에서 호출한다.
 */
@Component
public class OutboxWorker {

    private final OutboxEventRepository repository;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public OutboxWorker(OutboxEventRepository repository, NotificationDispatcher dispatcher, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /** PROCESSING 고착 복구: cutoff 이전에 선점된 이벤트를 PENDING 으로 되돌림. */
    @Transactional
    public int reap(LocalDateTime cutoff) {
        return repository.reclaimStuck(cutoff);
    }

    /** 선점: PENDING & due 이벤트를 SKIP LOCKED 로 잡아 PROCESSING 표시, id 목록 반환. */
    @Transactional
    public List<Long> claim(LocalDateTime now, int limit) {
        List<OutboxEvent> events = repository.findClaimable(now, limit);
        events.forEach(e -> e.markProcessing(now));
        return events.stream().map(OutboxEvent::getId).toList();
    }

    /** 발행: dispatch + PUBLISHED 를 한 트랜잭션에 커밋(알림 생성과 상태 종결이 원자적). */
    @Transactional
    public void publishOne(Long id) {
        OutboxEvent event = repository.findById(id).orElseThrow();
        dispatcher.dispatch(event);
        event.markPublished(LocalDateTime.now(clock));
    }

    /** 실패 기록: 별 트랜잭션(REQUIRES_NEW)으로 attempts/백오프/DEAD 를 보존. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, String error) {
        OutboxEvent event = repository.findById(id).orElseThrow();
        event.markFailed(LocalDateTime.now(clock), error);
    }
}
