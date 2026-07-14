package com.jipsanim.reservation.waiting;

import com.jipsanim.outbox.publisher.OutboxEventPublisher;
import com.jipsanim.reservation.queue.IssuedInvitation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 예약권 발급 알림 이벤트 적재(best-effort). 발급은 Redis 상태 변경이라 도메인 DB 커밋과 별개 →
 * 발급 감지 직후 짧은 트랜잭션으로 append. 중복은 event_key(invitationSeq 포함) 로 흡수(spec §4).
 * 실패가 대기/예약/sweep 본 흐름으로 전파되지 않도록 REQUIRES_NEW 로 분리하고, 호출부에서 예외를 삼킨다.
 */
@Component
public class InvitationEventRecorder {

    private final OutboxEventPublisher publisher;

    public InvitationEventRecorder(OutboxEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long slotId, IssuedInvitation invitation) {
        String eventKey = "WAITING_QUEUE_INVITATION_GRANTED:%d:%d:%d"
                .formatted(slotId, invitation.userId(), invitation.invitationSeq());
        publisher.append("WAITING", slotId, "WAITING_QUEUE_INVITATION_GRANTED", eventKey,
                Map.of("recipientUserId", invitation.userId(), "slotId", slotId));
    }
}
