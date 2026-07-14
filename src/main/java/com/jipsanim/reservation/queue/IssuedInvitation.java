package com.jipsanim.reservation.queue;

/**
 * 예약권 발급 결과. invitationSeq 는 Redis INCR(발급 generation)로, Outbox event_key 멱등에 쓰인다.
 */
public record IssuedInvitation(Long userId, long invitationSeq) {
}
