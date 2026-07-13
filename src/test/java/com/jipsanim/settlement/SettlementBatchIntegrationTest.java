package com.jipsanim.settlement;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.settlement.batch.SettlementBatchService;
import com.jipsanim.settlement.domain.Refund;
import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.dto.SettlementBatchResult;
import com.jipsanim.settlement.repository.RefundRepository;
import com.jipsanim.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3차 Phase 3: 월별 정산 배치(T325). 실제 now 데이터와 격리하기 위해 과거 월(2020~)만 사용.
 * payment 는 paidAt 을 정확히 지정하려고 JdbcTemplate 로 직접 삽입한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettlementBatchIntegrationTest {

    @Autowired
    SettlementBatchService batchService;
    @Autowired
    SettlementRepository settlementRepository;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicLong SEQ = new AtomicLong(900_000);

    private void insertPayment(long realtorId, long amount, String status, LocalDateTime paidAt) {
        long id = SEQ.incrementAndGet();
        jdbc.update("insert into payment(id, reservation_id, user_id, realtor_id, amount, status, paid_at, created_at, updated_at) "
                        + "values (?,?,?,?,?,?,?,?,?)",
                id, id, 1L, realtorId, amount, status, paidAt, LocalDateTime.now(), LocalDateTime.now());
    }

    private void insertRefund(long realtorId, long amount, LocalDateTime refundedAt) {
        long id = SEQ.incrementAndGet();
        refundRepository.save(Refund.create(id, id, realtorId, amount, null, refundedAt));
    }

    private Settlement settlement(long realtorId, String month) {
        return settlementRepository.findByRealtorIdAndSettlementMonth(realtorId, month).orElseThrow();
    }

    @Test
    @DisplayName("기본 배치: 결제 500000 - 환불 50000 → fee 90000, payout 360000, PENDING")
    void basicBatch() {
        long realtor = 91_001L;
        insertPayment(realtor, 500_000L, "PAID", LocalDateTime.of(2020, 1, 15, 10, 0));
        insertRefund(realtor, 50_000L, LocalDateTime.of(2020, 1, 20, 10, 0));

        SettlementBatchResult result = batchService.run(YearMonth.of(2020, 1));

        assertThat(result.month()).isEqualTo("2020-01");
        assertThat(result.createdCount()).isEqualTo(1);
        Settlement s = settlement(realtor, "2020-01");
        assertThat(s.getTotalPaymentAmount()).isEqualTo(500_000L);
        assertThat(s.getTotalRefundAmount()).isEqualTo(50_000L);
        assertThat(s.getPlatformFee()).isEqualTo(90_000L);
        assertThat(s.getPayoutAmount()).isEqualTo(360_000L);
        assertThat(s.isPending()).isTrue();
    }

    @Test
    @DisplayName("재실행 멱등: PENDING 재계산·UNIQUE 로 중복 정산 0(1건 유지)")
    void rerunUpdatesInPlace() {
        long realtor = 91_002L;
        insertPayment(realtor, 200_000L, "PAID", LocalDateTime.of(2020, 2, 10, 10, 0));

        SettlementBatchResult first = batchService.run(YearMonth.of(2020, 2));
        assertThat(first.createdCount()).isEqualTo(1);

        // 결제 추가 후 재실행 → 갱신(update), 새 행 생성 안 됨
        insertPayment(realtor, 300_000L, "PAID", LocalDateTime.of(2020, 2, 12, 10, 0));
        SettlementBatchResult second = batchService.run(YearMonth.of(2020, 2));
        assertThat(second.createdCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(1);

        assertThat(settlementRepository.findByRealtorIdAndSettlementMonth(realtor, "2020-02")).isPresent();
        Settlement s = settlement(realtor, "2020-02");
        assertThat(s.getTotalPaymentAmount()).isEqualTo(500_000L); // 200000+300000 재계산
    }

    @Test
    @DisplayName("이후 월 정산 존재 → 전체 요청 409(재계산 금지)")
    void laterMonthBlocksRecompute() {
        long realtor = 91_003L;
        insertPayment(realtor, 100_000L, "PAID", LocalDateTime.of(2020, 3, 5, 10, 0));
        insertPayment(realtor, 100_000L, "PAID", LocalDateTime.of(2020, 4, 5, 10, 0));
        batchService.run(YearMonth.of(2020, 3)); // 3월 정산 생성
        batchService.run(YearMonth.of(2020, 4)); // 4월(이후) 정산 생성

        // 3월 재실행 시도 → 이후월(4월) 존재 → 409
        assertThatThrownBy(() -> batchService.run(YearMonth.of(2020, 3)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이월 연쇄: 음수월 carry_over_out → 다음달 carry_over_in (당월 결제/환불 없어도 정산 생성)")
    void carryOverChain() {
        long realtor = 91_004L;
        // 5월: 환불>결제 → 음수 → carry_over_out 200000
        insertPayment(realtor, 100_000L, "PAID", LocalDateTime.of(2020, 5, 5, 10, 0));
        insertRefund(realtor, 300_000L, LocalDateTime.of(2020, 5, 10, 10, 0));
        batchService.run(YearMonth.of(2020, 5));
        Settlement may = settlement(realtor, "2020-05");
        assertThat(may.getPayoutAmount()).isZero();
        assertThat(may.getCarryOverOut()).isEqualTo(200_000L);

        // 6월: 당월 결제/환불 없음 → 그래도 전월 이월(200000)로 정산 생성돼야 함 (리뷰 P0-1)
        SettlementBatchResult june = batchService.run(YearMonth.of(2020, 6));
        assertThat(june.createdCount()).isEqualTo(1);
        Settlement jun = settlement(realtor, "2020-06");
        assertThat(jun.getCarryOverIn()).isEqualTo(200_000L);
        assertThat(jun.getTotalPaymentAmount()).isZero();
        assertThat(jun.getCarryOverOut()).isEqualTo(200_000L); // 여전히 소진 못 함 → 재이월
    }

    @Test
    @DisplayName("월 경계: 7월 결제 → 8월 환불 시 8월 정산에서 차감(누락/중복 0)")
    void monthBoundary() {
        long realtor = 91_005L;
        insertPayment(realtor, 500_000L, "PAID", LocalDateTime.of(2020, 7, 15, 10, 0));
        // 환불은 8월에 발생
        insertRefund(realtor, 500_000L, LocalDateTime.of(2020, 8, 3, 10, 0));

        batchService.run(YearMonth.of(2020, 7));
        Settlement jul = settlement(realtor, "2020-07");
        assertThat(jul.getTotalPaymentAmount()).isEqualTo(500_000L);
        assertThat(jul.getTotalRefundAmount()).isZero(); // 환불은 7월 아님
        assertThat(jul.getPlatformFee()).isEqualTo(100_000L);

        batchService.run(YearMonth.of(2020, 8));
        Settlement aug = settlement(realtor, "2020-08");
        assertThat(aug.getTotalPaymentAmount()).isZero();     // 8월 결제 없음
        assertThat(aug.getTotalRefundAmount()).isEqualTo(500_000L); // 8월 환불 차감
        assertThat(aug.getPayoutAmount()).isZero();
        assertThat(aug.getCarryOverOut()).isEqualTo(500_000L); // 음수 → 이월
    }
}
