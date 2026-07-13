package com.jipsanim.settlement.batch;

/** 중개사별 집계 금액(결제 합 / 환불 합) 프로젝션. */
public record RealtorAmount(Long realtorId, Long total) {
}
