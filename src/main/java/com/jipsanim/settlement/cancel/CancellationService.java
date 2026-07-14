package com.jipsanim.settlement.cancel;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.repository.ReservationRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.settlement.domain.Refund;
import com.jipsanim.settlement.dto.ReservationCancellationResponse;
import com.jipsanim.settlement.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 예약 취소 → 전액 환불(Mock) → 슬롯 재개방. (plan D1)
 * ① 잠금(Payment→Reservation→VisitSlot 전부 확보) → ② 검증(멱등/소유자/상태/24h) → ③ 변경(단일 트랜잭션).
 * 검증이 잠금 사이에 끼지 않도록 모든 잠금을 먼저 획득한다(리뷰 P0-2). Payment 를 reservationId 로 먼저 잠금(P0-4).
 */
@Service
public class CancellationService {

    /** 방문 시작 24h 전까지만 취소 허용. */
    private static final long CANCEL_DEADLINE_HOURS = 24;

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final VisitSlotRepository slotRepository;
    private final RefundRepository refundRepository;
    private final com.jipsanim.outbox.publisher.OutboxEventPublisher outbox;
    private final Clock clock;

    public CancellationService(PaymentRepository paymentRepository,
                               ReservationRepository reservationRepository,
                               VisitSlotRepository slotRepository,
                               RefundRepository refundRepository,
                               com.jipsanim.outbox.publisher.OutboxEventPublisher outbox,
                               Clock clock) {
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.refundRepository = refundRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ReservationCancellationResponse cancel(Long reservationId, Long userId) {
        // ① 잠금: Payment → Reservation → VisitSlot (락 순서 고정)
        Payment payment = paymentRepository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        VisitSlot slot = slotRepository.findByIdForUpdate(reservation.getVisitSlotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // ② 검증 (모든 잠금 확보 후)
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (reservation.isCancelled()) { // 멱등: 이미 취소 → 현재 상태 반환
            return response(reservation, payment, slot, refundedAmount(payment));
        }
        if (!reservation.isConfirmed()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "확정된 예약만 취소할 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!slot.getStartTime().isAfter(now.plusHours(CANCEL_DEADLINE_HOURS))) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "방문 24시간 이내에는 취소할 수 없습니다.");
        }

        // ③ 변경 (단일 트랜잭션)
        Refund refund = Refund.create(payment.getId(), reservationId, payment.getRealtorId(),
                payment.getAmount(), null, now);
        refundRepository.save(refund); // payment_id UNIQUE → 중복 환불 시 DataIntegrityViolation(409 CONFLICT)
        payment.refund();              // PAID → REFUNDED (paidAt 유지)
        reservation.cancel();          // CONFIRMED → CANCELLED (active_reservation_key=null)
        slot.reopen();                 // RESERVED → OPEN (재예약 허용)

        outbox.append("RESERVATION", reservationId, "VISIT_RESERVATION_CANCELLED",
                "VISIT_RESERVATION_CANCELLED:" + reservationId,
                java.util.Map.of("recipientUserId", userId, "reservationId", reservationId));
        outbox.append("PAYMENT", payment.getId(), "REFUND_COMPLETED",
                "REFUND_COMPLETED:" + payment.getId(),
                java.util.Map.of("recipientUserId", userId, "paymentId", payment.getId(),
                        "refundAmount", refund.getRefundAmount()));

        return response(reservation, payment, slot, refund.getRefundAmount());
    }

    private Long refundedAmount(Payment payment) {
        return refundRepository.findByPaymentId(payment.getId())
                .map(Refund::getRefundAmount)
                .orElse(payment.getAmount());
    }

    private ReservationCancellationResponse response(Reservation r, Payment p, VisitSlot s, Long refundAmount) {
        return new ReservationCancellationResponse(
                r.getId(), r.getStatus(), p.getStatus(), refundAmount, s.getStatus());
    }
}
