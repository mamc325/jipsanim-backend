package com.jipsanim.settlement.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 중개사 월별 정산. UNIQUE(realtor_id, settlement_month) 로 월별 중복 정산 방지.
 * 계산식 정본은 spec §3(이월 먼저 차감 후 수수료, floor 절사). 음수 정산은 carry_over_out 으로 다음 달 이월.
 */
@Entity
@Table(name = "settlement",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_realtor_month",
                columnNames = {"realtor_id", "settlement_month"}),
        indexes = {
                @Index(name = "idx_settlement_status", columnList = "status"),
                @Index(name = "idx_settlement_month", columnList = "settlement_month")
        })
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "realtor_id", nullable = false)
    private Long realtorId;

    @Column(name = "settlement_month", nullable = false, length = 7)
    private String settlementMonth; // YYYY-MM

    @Column(name = "total_payment_amount", nullable = false)
    private Long totalPaymentAmount;

    @Column(name = "total_refund_amount", nullable = false)
    private Long totalRefundAmount;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount; // 참고 지표: total_payment - total_refund

    @Column(name = "carry_over_in", nullable = false)
    private Long carryOverIn; // 전월 이월 차감액(≥0)

    @Column(name = "platform_fee", nullable = false)
    private Long platformFee;

    @Column(name = "carry_over_out", nullable = false)
    private Long carryOverOut; // 당월 발생 이월액(≥0) → 다음 달 carry_over_in

    @Column(name = "payout_amount", nullable = false)
    private Long payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    protected Settlement() {
    }

    private Settlement(Long realtorId, String settlementMonth, SettlementAmounts amounts) {
        this.realtorId = realtorId;
        this.settlementMonth = settlementMonth;
        this.status = SettlementStatus.PENDING;
        applyAmounts(amounts);
    }

    public static Settlement create(Long realtorId, String settlementMonth, SettlementAmounts amounts) {
        return new Settlement(realtorId, settlementMonth, amounts);
    }

    /** 배치 재실행 시 PENDING 정산의 금액 재계산. PENDING 이 아니면 호출 금지(배치가 skip 판단). */
    public void recalculate(SettlementAmounts amounts) {
        if (status != SettlementStatus.PENDING) {
            throw new IllegalStateException("PENDING 정산만 재계산할 수 있습니다: " + status);
        }
        applyAmounts(amounts);
    }

    private void applyAmounts(SettlementAmounts a) {
        this.totalPaymentAmount = a.totalPayment();
        this.totalRefundAmount = a.totalRefund();
        this.netAmount = a.netAmount();
        this.carryOverIn = a.carryOverIn();
        this.platformFee = a.platformFee();
        this.carryOverOut = a.carryOverOut();
        this.payoutAmount = a.payoutAmount();
    }

    /** PENDING→CONFIRMED. */
    public void confirm() {
        if (status != SettlementStatus.PENDING) {
            throw new IllegalStateException("PENDING 정산만 확정할 수 있습니다: " + status);
        }
        this.status = SettlementStatus.CONFIRMED;
    }

    /** CONFIRMED→PAID. */
    public void payout() {
        if (status != SettlementStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 정산만 지급할 수 있습니다: " + status);
        }
        this.status = SettlementStatus.PAID;
    }

    public boolean isPending() {
        return status == SettlementStatus.PENDING;
    }

    public boolean isConfirmed() {
        return status == SettlementStatus.CONFIRMED;
    }

    public boolean isPaid() {
        return status == SettlementStatus.PAID;
    }

    public Long getId() {
        return id;
    }

    public Long getRealtorId() {
        return realtorId;
    }

    public String getSettlementMonth() {
        return settlementMonth;
    }

    public Long getTotalPaymentAmount() {
        return totalPaymentAmount;
    }

    public Long getTotalRefundAmount() {
        return totalRefundAmount;
    }

    public Long getNetAmount() {
        return netAmount;
    }

    public Long getCarryOverIn() {
        return carryOverIn;
    }

    public Long getPlatformFee() {
        return platformFee;
    }

    public Long getCarryOverOut() {
        return carryOverOut;
    }

    public Long getPayoutAmount() {
        return payoutAmount;
    }

    public SettlementStatus getStatus() {
        return status;
    }
}
