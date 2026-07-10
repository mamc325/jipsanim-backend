package com.jipsanim.reservation.slot.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record VisitSlotCreateRequest(
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
