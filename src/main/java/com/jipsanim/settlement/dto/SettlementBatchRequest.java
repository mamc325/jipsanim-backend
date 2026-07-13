package com.jipsanim.settlement.dto;

/** 정산 배치 요청. month 미지정(null) 시 전월. 형식 YYYY-MM. */
public record SettlementBatchRequest(String month) {
}
