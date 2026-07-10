package com.jipsanim.reservation.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.reservation.config.ReservationProperties;
import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.dto.ReservationCreateResponse;
import com.jipsanim.reservation.dto.ReservationSummaryResponse;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.repository.ReservationRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 생성: 예약권(토큰) 소유자만, 잔여 PTTL 기준 expires_at, Reservation(PENDING)+Payment(READY) 동시 생성.
 * 멱등(기존 활성 PENDING 반환) + 선제 만료 정리(active_reservation_key 충돌 회피). (plan D2, 리뷰-1/2)
 */
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final VisitSlotRepository slotRepository;
    private final WaitingQueueService queue;
    private final long feeAmount;

    public ReservationService(ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                              VisitSlotRepository slotRepository, WaitingQueueService queue,
                              ReservationProperties properties) {
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.slotRepository = slotRepository;
        this.queue = queue;
        this.feeAmount = properties.feeAmount();
    }

    @Transactional
    public ReservationCreateResponse create(Long userId, Long slotId) {
        if (!queue.hasToken(slotId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "예약권이 없습니다.");
        }
        long remainingTtl = queue.tokenTtlSeconds(slotId);
        if (remainingTtl <= 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "예약권이 만료되었습니다.");
        }
        VisitSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (slot.getStatus() != VisitSlotStatus.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "예약 가능한 슬롯이 아닙니다.");
        }

        // 멱등: 이 사용자의 활성 PENDING 예약이 있으면 그대로 반환
        var existing = reservationRepository.findFirstByVisitSlotIdAndUserIdAndStatus(
                slotId, userId, ReservationStatus.PENDING_PAYMENT);
        if (existing.isPresent()) {
            Reservation r = existing.get();
            Payment p = paymentRepository.findByReservationId(r.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
            return toResponse(r, p, remainingTtl);
        }

        // 선제 만료 정리(리뷰-2): 슬롯을 점유 중인 만료 PENDING 을 정리해 active_reservation_key 충돌 회피
        reservationRepository.findFirstByVisitSlotIdAndStatus(slotId, ReservationStatus.PENDING_PAYMENT)
                .filter(blocking -> blocking.isExpiredAt(LocalDateTime.now()))
                .ifPresent(this::expireStale);
        reservationRepository.flush(); // 만료 UPDATE(active_key=null)를 insert 전에 반영

        Reservation reservation = reservationRepository.save(Reservation.create(
                userId, slot.getProperty().getId(), slotId, LocalDateTime.now().plusSeconds(remainingTtl)));
        Payment payment = paymentRepository.save(Payment.create(
                reservation.getId(), userId, slot.getProperty().getRealtor().getId(), feeAmount));
        return toResponse(reservation, payment, remainingTtl);
    }

    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> myReservations(Long userId) {
        return reservationRepository.findSummaries(userId);
    }

    private void expireStale(Reservation blocking) {
        blocking.expire();
        paymentRepository.findByReservationId(blocking.getId()).ifPresent(Payment::fail);
    }

    private ReservationCreateResponse toResponse(Reservation r, Payment p, long remainingTtl) {
        return new ReservationCreateResponse(r.getId(), p.getId(), r.getStatus(), p.getAmount(), remainingTtl);
    }
}
