package com.jipsanim.reservation.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_reservation", columnNames = "reservation_id"))
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "realtor_id")
    private Long realtorId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    protected Payment() {
    }

    private Payment(Long reservationId, Long userId, Long realtorId, Long amount) {
        this.reservationId = reservationId;
        this.userId = userId;
        this.realtorId = realtorId;
        this.amount = amount;
        this.status = PaymentStatus.READY;
    }

    public static Payment create(Long reservationId, Long userId, Long realtorId, Long amount) {
        return new Payment(reservationId, userId, realtorId, amount);
    }

    public void pay() {
        this.status = PaymentStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }

    /** 3차: 취소 환불. PAID→REFUNDED 만 허용, paidAt 유지(정산이 paidAt 에 의존). */
    public void refund() {
        if (this.status != PaymentStatus.PAID) {
            throw new IllegalStateException("환불은 PAID 결제에만 가능합니다: " + this.status);
        }
        this.status = PaymentStatus.REFUNDED;
    }

    public boolean isReady() {
        return status == PaymentStatus.READY;
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public boolean isFailed() {
        return status == PaymentStatus.FAILED;
    }

    public boolean isRefunded() {
        return status == PaymentStatus.REFUNDED;
    }

    public Long getRealtorId() {
        return realtorId;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}
