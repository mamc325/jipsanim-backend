package com.jipsanim.reservation.repository;

import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.dto.ReservationSummaryResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 멱등: 해당 슬롯에 이 사용자의 활성 PENDING 예약 */
    Optional<Reservation> findFirstByVisitSlotIdAndUserIdAndStatus(Long visitSlotId, Long userId, ReservationStatus status);

    /** 확정/실패 시 예약 잠금 (락 순서 2번) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /** sweep: 만료된 PENDING 예약 (Phase 6) */
    @Query("select r from Reservation r where r.status = com.jipsanim.reservation.domain.ReservationStatus.PENDING_PAYMENT "
            + "and r.expiresAt < :now")
    List<Reservation> findExpiredPending(@Param("now") LocalDateTime now);

    /** 슬롯을 점유 중인 PENDING 예약(다른 사용자 포함) — 재예약 시 만료 정리 대상 */
    Optional<Reservation> findFirstByVisitSlotIdAndStatus(Long visitSlotId, ReservationStatus status);

    @Query("select new com.jipsanim.reservation.dto.ReservationSummaryResponse("
            + "r.id, r.propertyId, r.visitSlotId, r.status, p.amount, r.reservedAt, r.confirmedAt) "
            + "from Reservation r, com.jipsanim.reservation.domain.Payment p "
            + "where p.reservationId = r.id and r.userId = :userId order by r.reservedAt desc")
    List<ReservationSummaryResponse> findSummaries(@Param("userId") Long userId);
}
