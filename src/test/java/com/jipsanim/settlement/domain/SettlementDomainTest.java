package com.jipsanim.settlement.domain;

import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.PaymentStatus;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3차 Phase 1: 환불/정산 관련 도메인 상태전이(constitution VIII).
 */
class SettlementDomainTest {

    @Test
    @DisplayName("Payment.refund(): PAID→REFUNDED, paidAt 유지")
    void refundFromPaid() {
        Payment payment = Payment.create(1L, 10L, 20L, 10000L);
        payment.pay();

        payment.refund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.isRefunded()).isTrue();
    }

    @Test
    @DisplayName("Payment.refund(): PAID 아니면 예외 (READY/FAILED)")
    void refundOnlyFromPaid() {
        Payment ready = Payment.create(1L, 10L, 20L, 10000L);
        assertThatThrownBy(ready::refund).isInstanceOf(IllegalStateException.class);

        Payment failed = Payment.create(2L, 10L, 20L, 10000L);
        failed.fail();
        assertThatThrownBy(failed::refund).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("VisitSlot.reopen(): RESERVED→OPEN")
    void reopenFromReserved() {
        VisitSlot slot = VisitSlot.create(null, null, null);
        slot.reserve();

        slot.reopen();

        assertThat(slot.getStatus()).isEqualTo(VisitSlotStatus.OPEN);
    }

    @Test
    @DisplayName("VisitSlot.reopen(): RESERVED 아니면 예외")
    void reopenOnlyFromReserved() {
        VisitSlot slot = VisitSlot.create(null, null, null); // OPEN
        assertThatThrownBy(slot::reopen).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Settlement 상태전이: PENDING→CONFIRMED→PAID")
    void settlementLifecycle() {
        Settlement s = Settlement.create(5L, "2026-07", amounts());

        assertThat(s.isPending()).isTrue();
        s.confirm();
        assertThat(s.isConfirmed()).isTrue();
        s.payout();
        assertThat(s.isPaid()).isTrue();
    }

    @Test
    @DisplayName("Settlement.payout(): CONFIRMED 아니면 예외(PENDING 지급 불가)")
    void payoutRequiresConfirmed() {
        Settlement s = Settlement.create(5L, "2026-07", amounts());
        assertThatThrownBy(s::payout).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Settlement.recalculate(): PENDING 만 허용")
    void recalculateOnlyPending() {
        Settlement s = Settlement.create(5L, "2026-07", amounts());
        s.recalculate(amounts()); // PENDING OK
        s.confirm();
        assertThatThrownBy(() -> s.recalculate(amounts())).isInstanceOf(IllegalStateException.class);
    }

    private SettlementAmounts amounts() {
        return new SettlementAmounts(500000L, 50000L, 450000L, 0L, 90000L, 0L, 360000L);
    }
}
