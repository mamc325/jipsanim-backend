package com.jipsanim.settlement.batch;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.domain.SettlementAmounts;
import com.jipsanim.settlement.dto.SettlementBatchResult;
import com.jipsanim.settlement.repository.RefundRepository;
import com.jipsanim.settlement.repository.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 월별 정산 배치 (plan D2, spec §3). 동기 실행 후 결과 반환(200, job 엔티티 없음, 리뷰 P0-3).
 * 대상 realtor = 당월 결제 ∪ 당월 환불 ∪ 전월 carry_over_out>0 (이월 연속성, 리뷰 P0-1).
 * 이후 월 정산이 같은 realtor 에게 존재하면 전체 요청 409(부분 성공 없음, 리뷰 P0-2).
 */
@Service
public class SettlementBatchService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementCalculator calculator;
    private final Clock clock;

    public SettlementBatchService(PaymentRepository paymentRepository,
                                  RefundRepository refundRepository,
                                  SettlementRepository settlementRepository,
                                  SettlementCalculator calculator,
                                  Clock clock) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.settlementRepository = settlementRepository;
        this.calculator = calculator;
        this.clock = clock;
    }

    /** month=null 이면 전월(Clock 기준). */
    @Transactional
    public SettlementBatchResult run(YearMonth month) {
        YearMonth target = (month != null) ? month : YearMonth.now(clock).minusMonths(1);
        String monthStr = target.toString();          // YYYY-MM
        String prevMonthStr = target.minusMonths(1).toString();
        LocalDateTime start = target.atDay(1).atStartOfDay();
        LocalDateTime end = target.plusMonths(1).atDay(1).atStartOfDay();

        Map<Long, Long> payments = toMap(paymentRepository.sumPaymentsByRealtor(start, end));
        Map<Long, Long> refunds = toMap(refundRepository.sumRefundsByRealtor(start, end));
        List<Settlement> prevCarry = settlementRepository
                .findBySettlementMonthAndCarryOverOutGreaterThan(prevMonthStr, 0L);
        Map<Long, Long> carryIn = new HashMap<>();
        for (Settlement s : prevCarry) {
            carryIn.put(s.getRealtorId(), s.getCarryOverOut());
        }

        // 대상 realtor 합집합 (결제 ∪ 환불 ∪ 전월 이월)
        Set<Long> realtorIds = new LinkedHashSet<>();
        realtorIds.addAll(payments.keySet());
        realtorIds.addAll(refunds.keySet());
        realtorIds.addAll(carryIn.keySet());

        // 선검사: 이후 월 정산이 존재하는 realtor 가 하나라도 있으면 전체 409 (carry_over 연쇄 회피)
        for (Long realtorId : realtorIds) {
            if (settlementRepository.existsByRealtorIdAndSettlementMonthGreaterThan(realtorId, monthStr)) {
                throw new BusinessException(ErrorCode.INVALID_STATE,
                        "이후 월 정산이 존재하는 중개사가 있어 재계산할 수 없습니다: realtorId=" + realtorId);
            }
        }

        int created = 0, updated = 0, skipped = 0;
        for (Long realtorId : realtorIds) {
            long totalPayment = payments.getOrDefault(realtorId, 0L);
            long totalRefund = refunds.getOrDefault(realtorId, 0L);
            long carryOverIn = carryIn.getOrDefault(realtorId, 0L);
            SettlementAmounts amounts = calculator.calculate(totalPayment, totalRefund, carryOverIn);

            Settlement existing = settlementRepository
                    .findByRealtorIdAndSettlementMonth(realtorId, monthStr).orElse(null);
            if (existing == null) {
                settlementRepository.save(Settlement.create(realtorId, monthStr, amounts));
                created++;
            } else if (existing.isPending()) {
                existing.recalculate(amounts);
                updated++;
            } else {
                skipped++; // CONFIRMED / PAID → 재계산 금지
            }
        }
        return new SettlementBatchResult(monthStr, created, updated, skipped);
    }

    private Map<Long, Long> toMap(List<RealtorAmount> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (RealtorAmount r : rows) {
            map.put(r.realtorId(), r.total() != null ? r.total() : 0L);
        }
        return map;
    }
}
