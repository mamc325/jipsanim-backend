package com.jipsanim.outbox.worker;

import com.jipsanim.outbox.domain.OutboxEvent;

/**
 * Outbox 이벤트 유형별 처리기. Worker 가 supports 되는 핸들러로 위임한다(알림 vs ES 색인 등).
 * 지원 핸들러가 없으면 Worker 는 실패 처리(재시도/DEAD).
 */
public interface OutboxEventHandler {

    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
