package com.jipsanim.reservation.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.dto.PaymentConfirmationResponse;
import com.jipsanim.reservation.dto.PaymentFailureResponse;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.repository.ReservationRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * Mock 결제 확정/실패. 락 순서 Payment→Reservation→VisitSlot(sweep 과 동일), 멱등, Redis 정리는 커밋 후.
 * (plan D3/D4, 리뷰-3)
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final VisitSlotRepository slotRepository;
    private final WaitingQueueService queue;

    public PaymentService(PaymentRepository paymentRepository, ReservationRepository reservationRepository,
                          VisitSlotRepository slotRepository, WaitingQueueService queue) {
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.queue = queue;
    }

    @Transactional
    public PaymentConfirmationResponse confirm(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!payment.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Reservation reservation = reservationRepository.findByIdForUpdate(payment.getReservationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Long slotId = reservation.getVisitSlotId();

        if (payment.isPaid()) { // 멱등: 이미 확정 → 현재 상태 반환(토큰 검사 없이, P1-b)
            return confirmation(payment, reservation, slotStatus(slotId));
        }
        if (!payment.isReady()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "결제 확정 대상이 아닙니다.");
        }
        if (!queue.hasToken(slotId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "예약권이 없습니다.");
        }
        if (reservation.isExpiredAt(LocalDateTime.now())) {
            queue.releaseTokenIfOwner(slotId, userId); // 토큰 회수(DB EXPIRED 는 sweep 이 확정)
            throw new BusinessException(ErrorCode.INVALID_STATE, "예약권이 만료되었습니다.");
        }

        VisitSlot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!slot.isOpen()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 예약된 슬롯입니다.");
        }
        payment.pay();
        reservation.confirm();
        slot.reserve();
        afterCommit(() -> queue.cleanupSlot(slotId)); // 확정 → 토큰/큐 정리
        return confirmation(payment, reservation, VisitSlotStatus.RESERVED);
    }

    @Transactional
    public PaymentFailureResponse fail(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!payment.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Reservation reservation = reservationRepository.findByIdForUpdate(payment.getReservationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (payment.isFailed()) { // 멱등: FAILED 재호출 → 200
            return new PaymentFailureResponse(payment.getId(), payment.getStatus(), reservation.getStatus());
        }
        if (payment.isPaid()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "확정된 결제는 실패 처리할 수 없습니다.");
        }
        payment.fail();
        reservation.expire();
        Long slotId = reservation.getVisitSlotId();
        afterCommit(() -> queue.releaseTokenIfOwner(slotId, userId)); // 토큰만 회수(큐 유지)
        return new PaymentFailureResponse(payment.getId(), payment.getStatus(), reservation.getStatus());
    }

    private VisitSlotStatus slotStatus(Long slotId) {
        return slotRepository.findById(slotId).map(VisitSlot::getStatus).orElse(null);
    }

    private PaymentConfirmationResponse confirmation(Payment p, Reservation r, VisitSlotStatus slotStatus) {
        return new PaymentConfirmationResponse(p.getId(), r.getId(), p.getStatus(), r.getStatus(), slotStatus);
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
