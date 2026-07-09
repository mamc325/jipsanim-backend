package com.jipsanim.external.molit;

import com.jipsanim.property.domain.DealType;

import java.math.BigDecimal;

/**
 * 실거래가 1건(정규화). 금액은 원 단위. 월세 0 이면 JEONSE, 그 외 MONTHLY_RENT.
 */
public record OfficetelRentTransaction(
        String sigunguCode,
        DealType dealType,
        long deposit,
        long monthlyRent,
        BigDecimal area,
        int dealYear,
        int dealMonth) {

    public String yearMonth() {
        return "%04d-%02d".formatted(dealYear, dealMonth);
    }
}
