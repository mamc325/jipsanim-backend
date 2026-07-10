package com.jipsanim.reservation.sweep;

import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.repository.ReservationRepository;
import com.jipsanim.reservation.waiting.WaitingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 만료 정리 + 재발급 백스톱 (plan D1, 리뷰 순서).
 * 순서: ①만료 PENDING 정리(락 후 재확인) → ②slot OPEN 확인·발급 → ③빈 큐 active-set 제거.
 * 만료 정리를 발급보다 먼저 해야 active_reservation_key 때문에 다음 예약이 409 나지 않는다.
 */
@Service
public class SweepService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final WaitingService waitingService;
    private final WaitingQueueService queue;

    public SweepService(ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                        WaitingService waitingService, WaitingQueueService queue) {
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.waitingService = waitingService;
        this.queue = queue;
    }

    @Transactional
    public void sweep() {
        expireStalePendings();
        reissueActiveSlots();
    }

    private void expireStalePendings() {
        LocalDateTime now = LocalDateTime.now();
        for (Reservation candidate : reservationRepository.findExpiredPending(now)) {
            // 락 순서 Payment → Reservation (confirm 과 동일)
            Payment payment = paymentRepository.findByReservationId(candidate.getId()).orElse(null);
            Payment lockedPayment = payment == null ? null
                    : paymentRepository.findByIdForUpdate(payment.getId()).orElse(null);
            Reservation locked = reservationRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (locked != null && locked.isExpiredAt(now)) { // 여전히 PENDING·만료 (PAID/CONFIRMED 는 제외됨)
                locked.expire();
                if (lockedPayment != null && lockedPayment.isReady()) {
                    lockedPayment.fail();
                }
            }
        }
    }

    private void reissueActiveSlots() {
        for (Long slotId : queue.activeSlots()) {
            waitingService.tryIssueIfSlotOpen(slotId);
            if (queue.isQueueEmpty(slotId) && queue.tokenOwner(slotId) == null) {
                queue.removeActiveSlot(slotId);
            }
        }
    }
}
