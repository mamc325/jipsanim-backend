package com.jipsanim.settlement.repository;

import com.jipsanim.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByRealtorIdAndSettlementMonth(Long realtorId, String settlementMonth);

    /** 배치: 재계산 금지 판정 — 같은 realtor 에게 대상 월보다 이후 월 정산이 존재하는지 (리뷰 P0-2). */
    boolean existsByRealtorIdAndSettlementMonthGreaterThan(Long realtorId, String settlementMonth);

    /** 배치: 전월 carry_over_out > 0 인 정산 조회 (이월 대상 realtor 합집합, 리뷰 P0-1). */
    List<Settlement> findBySettlementMonthAndCarryOverOutGreaterThan(String settlementMonth, Long threshold);

    List<Settlement> findByRealtorIdOrderBySettlementMonthDesc(Long realtorId);
}
