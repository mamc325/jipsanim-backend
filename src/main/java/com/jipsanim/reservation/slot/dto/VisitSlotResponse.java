package com.jipsanim.reservation.slot.dto;

import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;

import java.time.LocalDateTime;

public record VisitSlotResponse(
        Long visitSlotId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        VisitSlotStatus status) {

    public static VisitSlotResponse from(VisitSlot slot) {
        return new VisitSlotResponse(slot.getId(), slot.getStartTime(), slot.getEndTime(), slot.getStatus());
    }
}
