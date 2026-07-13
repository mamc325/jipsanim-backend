package com.jipsanim.reservation.repository;

import com.jipsanim.reservation.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReservationId(Long reservationId);

    /** 결제 확정/실패 시 잠금 (락 순서 1번) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /** 3차 취소: reservationId 로 Payment 를 먼저 잠금 (락 순서 P→R→V, 리뷰 P0-4) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reservationId = :reservationId")
    Optional<Payment> findByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    /**
     * 3차 정산 집계: 중개사별 결제 합. 결제는 paidAt 기준(환불되었어도 그 달엔 결제 발생),
     * status IN (PAID, REFUNDED). 반열림 구간 [start, end).
     */
    @Query("select new com.jipsanim.settlement.batch.RealtorAmount(p.realtorId, sum(p.amount)) "
            + "from Payment p "
            + "where p.status in (com.jipsanim.reservation.domain.PaymentStatus.PAID, "
            + "                   com.jipsanim.reservation.domain.PaymentStatus.REFUNDED) "
            + "and p.realtorId is not null and p.paidAt >= :start and p.paidAt < :end "
            + "group by p.realtorId")
    java.util.List<com.jipsanim.settlement.batch.RealtorAmount> sumPaymentsByRealtor(
            @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);
}
