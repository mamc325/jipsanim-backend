package com.jipsanim.reservation.waiting.dto;

public record WaitingEntryResponse(Long slotId, long position, boolean tokenGranted) {
}
