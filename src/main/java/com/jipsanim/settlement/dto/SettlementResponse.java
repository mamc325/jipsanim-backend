package com.jipsanim.settlement.dto;

import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.domain.SettlementStatus;

public record SettlementResponse(
        Long settlementId,
        Long realtorId,
        String settlementMonth,
        Long totalPaymentAmount,
        Long totalRefundAmount,
        Long netAmount,
        Long platformFee,
        Long carryOverIn,
        Long carryOverOut,
        Long payoutAmount,
        SettlementStatus status
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
                s.getId(), s.getRealtorId(), s.getSettlementMonth(),
                s.getTotalPaymentAmount(), s.getTotalRefundAmount(), s.getNetAmount(),
                s.getPlatformFee(), s.getCarryOverIn(), s.getCarryOverOut(),
                s.getPayoutAmount(), s.getStatus());
    }
}
