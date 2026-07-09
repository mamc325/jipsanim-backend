package com.jipsanim.pricestandard.stats;

import com.jipsanim.pricestandard.domain.CalcMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RangeCalculatorTest {

    private final RangeCalculator calculator = new RangeCalculator();

    @Test
    @DisplayName("PERCENTILE: 1~10 → [p10=2, p90=9]")
    void percentile() {
        long[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        PriceRange range = calculator.calculate(values, CalcMethod.PERCENTILE);

        assertThat(range.min()).isEqualTo(2);
        assertThat(range.max()).isEqualTo(9);
    }

    @Test
    @DisplayName("IQR: 극단 이상치(1000)는 정상 범위 상한 밖으로 배제된다")
    void iqrExcludesOutlier() {
        long[] values = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 1000};

        PriceRange range = calculator.calculate(values, CalcMethod.IQR);

        assertThat(range.max()).isLessThan(1000);
        assertThat(range.min()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("표본 1건이면 min=max=그 값")
    void singleValue() {
        PriceRange range = calculator.calculate(new long[]{500}, CalcMethod.IQR);

        assertThat(range.min()).isEqualTo(500);
        assertThat(range.max()).isEqualTo(500);
    }

    @Test
    @DisplayName("빈 표본은 [0,0]")
    void empty() {
        PriceRange range = calculator.calculate(new long[]{}, CalcMethod.IQR);

        assertThat(range.min()).isZero();
        assertThat(range.max()).isZero();
    }
}
