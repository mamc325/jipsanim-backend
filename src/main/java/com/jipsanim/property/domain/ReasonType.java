package com.jipsanim.property.domain;

public enum ReasonType {
    MISSING_REQUIRED_FIELD,
    MISSING_IMAGE,
    DESCRIPTION_TOO_SHORT,
    PRICE_OUT_OF_RANGE,
    ADDRESS_REGION_MISMATCH,
    DUPLICATE_SUSPECTED,
    /** 해당 지역 ACTIVE 기준이 없거나 표본 부족 → 자동 위험판정 대신 관리자 검토 */
    INSUFFICIENT_STANDARD
}
