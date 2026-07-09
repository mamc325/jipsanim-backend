package com.jipsanim.property.verification.event;

/**
 * 매물 승인 도메인 이벤트. (4차에서 Outbox 저장 리스너로 교체 — plan D6)
 */
public record PropertyApprovedEvent(Long propertyId) {
}
