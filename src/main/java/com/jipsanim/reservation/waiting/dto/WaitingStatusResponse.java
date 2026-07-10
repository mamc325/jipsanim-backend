package com.jipsanim.reservation.waiting.dto;

public record WaitingStatusResponse(Long slotId, long position, boolean tokenGranted, long tokenExpiresInSeconds) {
}
