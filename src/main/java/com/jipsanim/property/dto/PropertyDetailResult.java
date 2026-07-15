package com.jipsanim.property.dto;

/**
 * 상세 조회 결과 + 공개표현 여부(6차 P1). `countablePublicAccess=true` 는 서비스가 ACTIVE 공개표현을
 * 반환했음을 뜻한다 → 조회수 카운트·상세 캐시 게이트로 사용. 비공개(소유자/ADMIN 전용) 표현은 false.
 */
public record PropertyDetailResult(PropertyDetailResponse response, boolean countablePublicAccess) {
}
