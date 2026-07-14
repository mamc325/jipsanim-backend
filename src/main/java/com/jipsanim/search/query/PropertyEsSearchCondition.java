package com.jipsanim.search.query;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;

import java.math.BigDecimal;

/** ES 매물 검색 조건 (모두 optional). q=전문검색어, 나머지는 필터. */
public record PropertyEsSearchCondition(
        String q,
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

    public boolean hasQuery() {
        return q != null && !q.isBlank();
    }
}
