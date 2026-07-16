package com.jipsanim.settlement.dto;

import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.domain.SettlementStatus;

public record SettlementResponse(
        Long settlementId,
        Long realtorId,
        String realtorName,
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
        return from(s, null);
    }

    /** realtorName(Realtor.businessName) 은 조회 경로에서 배치 조회해 주입. 없으면 null. */
    public static SettlementResponse from(Settlement s, String realtorName) {
        return new SettlementResponse(
                s.getId(), s.getRealtorId(), realtorName, s.getSettlementMonth(),
                s.getTotalPaymentAmount(), s.getTotalRefundAmount(), s.getNetAmount(),
                s.getPlatformFee(), s.getCarryOverIn(), s.getCarryOverOut(),
                s.getPayoutAmount(), s.getStatus());
    }
}
