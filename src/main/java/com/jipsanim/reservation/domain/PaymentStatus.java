package com.jipsanim.reservation.domain;

public enum PaymentStatus {
    READY,
    PAID,
    FAILED,
    REFUNDED // 3차: 취소 시 PAID→REFUNDED
}
