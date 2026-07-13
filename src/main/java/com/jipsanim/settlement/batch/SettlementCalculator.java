package com.jipsanim.settlement.batch;

import com.jipsanim.settlement.domain.SettlementAmounts;
import org.springframework.stereotype.Component;

/**
 * 정산 계산 (spec §3 정본). 이월(carry_over_in)을 먼저 차감한 뒤 수수료를 매긴다
 * — 음수 이월을 갚는 달에 과다 수수료가 붙지 않도록(리뷰 P0-1).
 *
 * <pre>
 * net_amount      = total_payment - total_refund                 // 참고 지표
 * gross_available = total_payment - total_refund - carry_over_in // 이월 먼저 차감
 * platform_fee    = gross > 0 ? floor(gross * 0.20) : 0          // 원 단위 절사(floor)
 * payout_amount   = max(0, gross - platform_fee)
 * carry_over_out  = max(0, -gross)                               // 음수면 다음 달 이월
 * </pre>
 */
@Component
public class SettlementCalculator {

    private static final long FEE_RATE_NUMERATOR = 20;   // 20%
    private static final long FEE_RATE_DENOMINATOR = 100;

    public SettlementAmounts calculate(long totalPayment, long totalRefund, long carryOverIn) {
        long netAmount = totalPayment - totalRefund;
        long gross = netAmount - carryOverIn;

        // floor(gross * 0.20): gross > 0 이므로 정수 나눗셈(0 방향 절사)이 곧 floor.
        long platformFee = gross > 0 ? gross * FEE_RATE_NUMERATOR / FEE_RATE_DENOMINATOR : 0;
        long payoutAmount = Math.max(0, gross - platformFee);
        long carryOverOut = Math.max(0, -gross);

        return new SettlementAmounts(
                totalPayment, totalRefund, netAmount, carryOverIn,
                platformFee, carryOverOut, payoutAmount);
    }
}
