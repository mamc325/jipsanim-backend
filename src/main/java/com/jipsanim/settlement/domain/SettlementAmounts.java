package com.jipsanim.settlement.domain;

/**
 * 정산 금액 계산 결과 carrier. 계산식 정본(spec §3)은 Phase 3 SettlementCalculator 가 채운다.
 * - net_amount = total_payment - total_refund (참고 지표)
 * - gross_available = total_payment - total_refund - carry_over_in
 * - platform_fee = gross > 0 ? floor(gross * 0.20) : 0
 * - payout_amount = max(0, gross - platform_fee)
 * - carry_over_out = max(0, -gross)
 */
public record SettlementAmounts(
        long totalPayment,
        long totalRefund,
        long netAmount,
        long carryOverIn,
        long platformFee,
        long carryOverOut,
        long payoutAmount
) {
}
