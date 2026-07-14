package com.jipsanim.outbox.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.dto.OutboxEventResponse;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Outbox 모니터링/재처리(관리자). DEAD 이벤트를 PENDING 으로 되돌려 Worker 가 재발행하게 한다.
 */
@Service
public class OutboxAdminService {

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxAdminService(OutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventResponse> list(OutboxStatus status, Pageable pageable) {
        Page<OutboxEvent> page = (status == null)
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable);
        return page.map(OutboxEventResponse::from);
    }

    /** DEAD → PENDING 재처리. DEAD 아니면 409. */
    @Transactional
    public OutboxEventResponse reprocess(Long outboxEventId) {
        OutboxEvent event = repository.findById(outboxEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!event.isDead()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "DEAD 상태만 재처리할 수 있습니다.");
        }
        event.reset(LocalDateTime.now(clock));
        return OutboxEventResponse.from(event);
    }
}
