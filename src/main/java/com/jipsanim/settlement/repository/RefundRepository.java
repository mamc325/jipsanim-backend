package com.jipsanim.settlement.repository;

import com.jipsanim.settlement.batch.RealtorAmount;
import com.jipsanim.settlement.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPaymentId(Long paymentId);

    boolean existsByPaymentId(Long paymentId);

    /** 3차 정산 집계: 중개사별 환불 합. 환불은 refundedAt 기준(발생 월에 차감). 반열림 구간 [start, end). */
    @Query("select new com.jipsanim.settlement.batch.RealtorAmount(r.realtorId, sum(r.refundAmount)) "
            + "from Refund r "
            + "where r.realtorId is not null and r.refundedAt >= :start and r.refundedAt < :end "
            + "group by r.realtorId")
    List<RealtorAmount> sumRefundsByRealtor(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
