package com.jipsanim.settlement.batch;

import com.jipsanim.settlement.domain.SettlementAmounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    @Test
    @DisplayName("이월 없음: 500000 결제, 50000 환불 → fee 90000, payout 360000")
    void basic() {
        SettlementAmounts a = calculator.calculate(500_000L, 50_000L, 0L);

        assertThat(a.netAmount()).isEqualTo(450_000L);
        assertThat(a.platformFee()).isEqualTo(90_000L);   // floor(450000*0.2)
        assertThat(a.payoutAmount()).isEqualTo(360_000L);
        assertThat(a.carryOverOut()).isZero();
    }

    @Test
    @DisplayName("이월 먼저 차감 후 수수료: carry_over_in=100000 → gross=350000 기준 fee 70000")
    void carryOverDeductedBeforeFee() {
        // net=450000 이지만 이월 100000 먼저 차감 → gross=350000 에만 수수료
        SettlementAmounts a = calculator.calculate(500_000L, 50_000L, 100_000L);

        assertThat(a.carryOverIn()).isEqualTo(100_000L);
        assertThat(a.platformFee()).isEqualTo(70_000L);    // floor(350000*0.2), net*0.2(90000) 아님
        assertThat(a.payoutAmount()).isEqualTo(280_000L);  // 350000 - 70000
        assertThat(a.carryOverOut()).isZero();
    }

    @Test
    @DisplayName("floor 절사: gross=333333 → fee=66666 (66666.6 버림)")
    void floorRounding() {
        SettlementAmounts a = calculator.calculate(333_333L, 0L, 0L);

        assertThat(a.platformFee()).isEqualTo(66_666L);    // 333333*20/100 = 66666 (floor)
        assertThat(a.payoutAmount()).isEqualTo(266_667L);
    }

    @Test
    @DisplayName("음수 정산(환불>결제): payout 0, carry_over_out 발생, fee 0")
    void negativeCarriesOver() {
        SettlementAmounts a = calculator.calculate(100_000L, 300_000L, 0L);

        assertThat(a.netAmount()).isEqualTo(-200_000L);
        assertThat(a.platformFee()).isZero();
        assertThat(a.payoutAmount()).isZero();
        assertThat(a.carryOverOut()).isEqualTo(200_000L);  // 다음 달 carry_over_in
    }

    @Test
    @DisplayName("이월이 당월 순액보다 큼: 전부 이월로 소진, payout 0, 남은 이월 재발생")
    void carryOverLargerThanNet() {
        // net=100000, carry_over_in=300000 → gross=-200000 → payout 0, carry_out 200000
        SettlementAmounts a = calculator.calculate(100_000L, 0L, 300_000L);

        assertThat(a.platformFee()).isZero();
        assertThat(a.payoutAmount()).isZero();
        assertThat(a.carryOverOut()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("gross 정확히 0: fee 0, payout 0, carry_over_out 0")
    void grossZero() {
        SettlementAmounts a = calculator.calculate(100_000L, 0L, 100_000L);

        assertThat(a.platformFee()).isZero();
        assertThat(a.payoutAmount()).isZero();
        assertThat(a.carryOverOut()).isZero();
    }
}
