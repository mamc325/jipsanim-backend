package com.jipsanim.settlement.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 예약 취소 시 발생하는 전액 환불(Mock). payment_id UNIQUE 로 결제당 환불 1건 보장(중복 환불 방지).
 * refunded_at 은 월별 정산 집계 기준(환불은 발생 월에 차감).
 */
@Entity
@Table(name = "refund",
        uniqueConstraints = @UniqueConstraint(name = "uk_refund_payment", columnNames = "payment_id"),
        indexes = @Index(name = "idx_refund_settlement", columnList = "realtor_id, refunded_at"))
public class Refund extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "realtor_id")
    private Long realtorId;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    @Column(length = 255)
    private String reason;

    @Column(name = "refunded_at", nullable = false)
    private LocalDateTime refundedAt;

    protected Refund() {
    }

    private Refund(Long paymentId, Long reservationId, Long realtorId, Long refundAmount,
                   String reason, LocalDateTime refundedAt) {
        this.paymentId = paymentId;
        this.reservationId = reservationId;
        this.realtorId = realtorId;
        this.refundAmount = refundAmount;
        this.reason = reason;
        this.refundedAt = refundedAt;
    }

    public static Refund create(Long paymentId, Long reservationId, Long realtorId, Long refundAmount,
                                String reason, LocalDateTime refundedAt) {
        return new Refund(paymentId, reservationId, realtorId, refundAmount, reason, refundedAt);
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getRealtorId() {
        return realtorId;
    }

    public Long getRefundAmount() {
        return refundAmount;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }
}
