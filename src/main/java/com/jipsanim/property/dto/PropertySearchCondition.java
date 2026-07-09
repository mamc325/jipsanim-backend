package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;

import java.math.BigDecimal;

/** 매물 조건 검색 파라미터 (모두 optional). */
public record PropertySearchCondition(
        String regionName,
        String sigunguCode,
        DealType dealType,
        PropertyType propertyType,
        Long minDeposit,
        Long maxDeposit,
        Long minRent,
        Long maxRent,
        BigDecimal minArea,
        BigDecimal maxArea,
        Integer roomCount) {
}
