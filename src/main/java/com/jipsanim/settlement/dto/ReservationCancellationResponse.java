package com.jipsanim.settlement.dto;

import com.jipsanim.reservation.domain.PaymentStatus;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;

public record ReservationCancellationResponse(
        Long reservationId,
        ReservationStatus reservationStatus,
        PaymentStatus paymentStatus,
        Long refundAmount,
        VisitSlotStatus visitSlotStatus
) {
}
