package com.jipsanim.reservation.dto;

import com.jipsanim.reservation.domain.PaymentStatus;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;

public record PaymentConfirmationResponse(
        Long paymentId,
        Long reservationId,
        PaymentStatus paymentStatus,
        ReservationStatus reservationStatus,
        VisitSlotStatus visitSlotStatus) {
}
