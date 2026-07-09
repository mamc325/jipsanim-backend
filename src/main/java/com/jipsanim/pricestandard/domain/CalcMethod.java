package com.jipsanim.pricestandard.domain;

public enum CalcMethod {
    /** [p10, p90] */
    PERCENTILE,
    /** [Q1-1.5·IQR, Q3+1.5·IQR] (이상치 제외 정상 범위) */
    IQR
}
