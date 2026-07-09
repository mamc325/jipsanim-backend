package com.jipsanim.pricestandard.stats;

import com.jipsanim.pricestandard.domain.CalcMethod;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 표본에서 정상 가격 범위를 산출하는 순수 계산기 (Constitution III).
 * 단순 min/max 를 쓰지 않고 백분위(p10~p90) 또는 IQR 기반으로 이상치를 배제한다.
 */
@Component
public class RangeCalculator {

    public PriceRange calculate(long[] values, CalcMethod method) {
        if (values == null || values.length == 0) {
            return new PriceRange(0, 0);
        }
        double[] sorted = Arrays.stream(values).asDoubleStream().sorted().toArray();
        if (method == CalcMethod.PERCENTILE) {
            return new PriceRange(round(percentile(sorted, 10)), round(percentile(sorted, 90)));
        }
        double q1 = percentile(sorted, 25);
        double q3 = percentile(sorted, 75);
        double iqr = q3 - q1;
        double lower = Math.max(0, q1 - 1.5 * iqr);
        double upper = q3 + 1.5 * iqr;
        return new PriceRange(round(lower), round(upper));
    }

    /** 선형 보간(type R-7) 백분위. */
    private double percentile(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 1) {
            return sorted[0];
        }
        double rank = p / 100.0 * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sorted[lo];
        }
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
    }

    private long round(double value) {
        return Math.round(value);
    }
}
