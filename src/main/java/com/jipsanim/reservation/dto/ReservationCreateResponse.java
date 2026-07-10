package com.jipsanim.reservation.dto;

import com.jipsanim.reservation.domain.ReservationStatus;

public record ReservationCreateResponse(
        Long reservationId,
        Long paymentId,
        ReservationStatus status,
        Long amount,
        long expiresInSeconds) {
}
