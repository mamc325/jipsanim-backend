package com.jipsanim.reservation.waiting;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.reservation.queue.IssuedInvitation;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.reservation.waiting.dto.WaitingEntryResponse;
import com.jipsanim.reservation.waiting.dto.WaitingStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대기열 진입/순번 조회 + 발급 트리거. Redis 큐(WaitingQueueService)와 MySQL 슬롯 상태가 만나는 지점.
 * `tryIssueIfSlotOpen`: slot 이 OPEN 일 때만 발급, 아니면 Redis 정리(P1-3).
 */
@Service
public class WaitingService {

    private static final Logger log = LoggerFactory.getLogger(WaitingService.class);

    private final WaitingQueueService queue;
    private final VisitSlotRepository slotRepository;
    private final InvitationEventRecorder invitationEventRecorder;

    public WaitingService(WaitingQueueService queue, VisitSlotRepository slotRepository,
                          InvitationEventRecorder invitationEventRecorder) {
        this.queue = queue;
        this.slotRepository = slotRepository;
        this.invitationEventRecorder = invitationEventRecorder;
    }

    public WaitingEntryResponse enter(Long slotId, Long userId) {
        VisitSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (slot.getStatus() != VisitSlotStatus.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "대기 가능한 슬롯이 아닙니다.");
        }
        if (queue.hasToken(slotId, userId)) {
            throw new BusinessException(ErrorCode.ALREADY_GRANTED);
        }
        if (!queue.enqueue(slotId, userId)) {
            throw new BusinessException(ErrorCode.ALREADY_WAITING);
        }
        tryIssueIfSlotOpen(slotId);

        boolean granted = queue.hasToken(slotId, userId);
        long position = granted ? 0 : positionOf(slotId, userId);
        return new WaitingEntryResponse(slotId, position, granted);
    }

    public WaitingStatusResponse myStatus(Long slotId, Long userId) {
        tryIssueIfSlotOpen(slotId);
        // 토큰 우선 판정(발급되며 ZPOPMIN 으로 큐에서 빠져 rank 는 null 이므로, P2-1)
        if (queue.hasToken(slotId, userId)) {
            return new WaitingStatusResponse(slotId, 0, true, queue.tokenTtlSeconds(slotId));
        }
        Long rank = queue.rank(slotId, userId);
        if (rank == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new WaitingStatusResponse(slotId, rank, false, 0);
    }

    /** slot 이 OPEN 이면 발급 시도(발급 시 알림 이벤트 적재), 아니면 Redis 정리. sweep 도 사용. */
    public IssuedInvitation tryIssueIfSlotOpen(Long slotId) {
        VisitSlot slot = slotRepository.findById(slotId).orElse(null);
        if (slot != null && slot.getStatus() == VisitSlotStatus.OPEN) {
            IssuedInvitation invitation = queue.tryIssue(slotId);
            if (invitation != null) {
                recordInvitationBestEffort(slotId, invitation);
            }
            return invitation;
        }
        queue.cleanupSlot(slotId);
        return null;
    }

    /** 발급 알림 적재는 best-effort — 실패해도 발급/예약/sweep 본 흐름을 막지 않는다(REQUIRES_NEW + 예외 흡수). */
    private void recordInvitationBestEffort(Long slotId, IssuedInvitation invitation) {
        try {
            invitationEventRecorder.record(slotId, invitation);
        } catch (Exception e) {
            log.warn("예약권 발급 알림 적재 실패(best-effort, 무시): slotId={} userId={} err={}",
                    slotId, invitation.userId(), e.getMessage());
        }
    }

    private long positionOf(Long slotId, Long userId) {
        Long rank = queue.rank(slotId, userId);
        return rank == null ? 0 : rank;
    }
}
