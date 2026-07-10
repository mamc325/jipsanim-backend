package com.jipsanim.reservation.dto;

import com.jipsanim.reservation.domain.PaymentStatus;
import com.jipsanim.reservation.domain.ReservationStatus;

public record PaymentFailureResponse(
        Long paymentId,
        PaymentStatus paymentStatus,
        ReservationStatus reservationStatus) {
}
