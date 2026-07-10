package com.jipsanim.reservation.dto;

import com.jipsanim.reservation.domain.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationSummaryResponse(
        Long reservationId,
        Long propertyId,
        Long visitSlotId,
        ReservationStatus status,
        Long amount,
        LocalDateTime reservedAt,
        LocalDateTime confirmedAt) {
}
