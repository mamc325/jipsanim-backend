package com.jipsanim.settlement.repository;

import com.jipsanim.settlement.domain.Settlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByRealtorIdAndSettlementMonth(Long realtorId, String settlementMonth);

    /** 배치: 재계산 금지 판정 — 같은 realtor 에게 대상 월보다 이후 월 정산이 존재하는지 (리뷰 P0-2). */
    boolean existsByRealtorIdAndSettlementMonthGreaterThan(Long realtorId, String settlementMonth);

    /** 배치: 전월 carry_over_out > 0 인 정산 조회 (이월 대상 realtor 합집합, 리뷰 P0-1). */
    List<Settlement> findBySettlementMonthAndCarryOverOutGreaterThan(String settlementMonth, Long threshold);

    List<Settlement> findByRealtorIdOrderBySettlementMonthDesc(Long realtorId);

    /** 확정/지급 상태전이 시 잠금. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id")
    Optional<Settlement> findByIdForUpdate(@Param("id") Long id);

    /** /me: 중개사 본인 정산(month 선택). */
    @Query("select s from Settlement s where s.realtorId = :realtorId "
            + "and (:month is null or s.settlementMonth = :month) order by s.settlementMonth desc")
    List<Settlement> findMine(@Param("realtorId") Long realtorId, @Param("month") String month);

    /** /admin: month/realtor 선택 필터 + 페이지. */
    @Query("select s from Settlement s where (:month is null or s.settlementMonth = :month) "
            + "and (:realtorId is null or s.realtorId = :realtorId)")
    Page<Settlement> search(@Param("month") String month, @Param("realtorId") Long realtorId, Pageable pageable);
}
