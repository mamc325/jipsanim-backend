package com.jipsanim.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.domain.VisitSlotStatus;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.repository.SettlementRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3차 세로 슬라이스 E2E(T340): 예약확정 → 취소 → 환불 → 슬롯 재개방 →
 * 월별 정산 배치 → 집계 반영 → 관리자 확정 → 지급 → 중개사 조회.
 * 배치 집계를 위해 결제/환불 시각을 격리된 과거 월(2022-03)로 백데이트한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementE2EIntegrationTest {

    private static final String MONTH = "2022-03";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    VisitSlotRepository slotRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    SettlementRepository settlementRepository;
    @Autowired
    JwtTokenProvider jwt;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("E2E: 예약확정→취소→환불→배치집계→확정→지급→중개사 조회")
    void fullLifecycle() throws Exception {
        int n = SEQ.incrementAndGet();
        // --- 중개사/매물/슬롯(24h 이후 2개) ---
        User realtorUser = userRepository.save(User.create("settle.e2e.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(realtorUser, "공인", "010"));
        long realtorId = realtor.getId();
        String realtorTk = jwt.createAccessToken(realtorUser.getId(), Role.REALTOR);

        Property property = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        property.approve();
        propertyRepository.save(property);
        LocalDateTime base = LocalDateTime.now().plusDays(3).withNano(0);
        long slot1 = slotRepository.save(VisitSlot.create(property, base, base.plusMinutes(30))).getId();
        long slot2 = slotRepository.save(VisitSlot.create(property, base.plusHours(1), base.plusHours(1).plusMinutes(30))).getId();

        // --- 두 사용자 예약+결제 확정 ---
        String u1 = userToken("settle.e2e.u1." + n + "@test.com");
        String u2 = userToken("settle.e2e.u2." + n + "@test.com");
        long payment1 = reserveConfirm(slot1, u1);
        reserveConfirm(slot2, u2);

        // --- user1 취소 → 환불 + 슬롯 재개방 ---
        long reservationId = firstReservationId(u1);
        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.data.visitSlotStatus").value("OPEN"));
        Payment p1 = paymentRepository.findById(payment1).orElseThrow();
        assertThat(p1.getStatus().name()).isEqualTo("REFUNDED");
        assertThat(slotRepository.findById(slot1).orElseThrow().getStatus()).isEqualTo(VisitSlotStatus.OPEN);

        // --- 결제/환불 시각을 격리 월로 백데이트 (배치 집계 대상화) ---
        jdbc.update("update payment set paid_at = ? where realtor_id = ?",
                LocalDateTime.of(2022, 3, 10, 10, 0), realtorId);
        jdbc.update("update refund set refunded_at = ? where realtor_id = ?",
                LocalDateTime.of(2022, 3, 15, 10, 0), realtorId);

        // --- 관리자 배치 실행 ---
        String admin = adminToken("settle.e2e.admin" + n + "@test.com");
        mockMvc.perform(post("/api/admin/settlement-batch-jobs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"month\":\"" + MONTH + "\"}"))
                .andExpect(status().isOk());

        // --- 집계 검증: 결제 20000 - 환불 10000 = net 10000 → fee 2000, payout 8000 ---
        Settlement s = settlementRepository.findByRealtorIdAndSettlementMonth(realtorId, MONTH).orElseThrow();
        assertThat(s.getTotalPaymentAmount()).isEqualTo(20_000L); // PAID + REFUNDED 둘 다 결제로 집계
        assertThat(s.getTotalRefundAmount()).isEqualTo(10_000L);
        assertThat(s.getPlatformFee()).isEqualTo(2_000L);         // floor(10000*0.2)
        assertThat(s.getPayoutAmount()).isEqualTo(8_000L);
        assertThat(s.isPending()).isTrue();
        long settlementId = s.getId();

        // --- 관리자 확정 → 지급 ---
        mockMvc.perform(post("/api/admin/settlements/{id}/confirmation", settlementId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mockMvc.perform(post("/api/admin/settlements/{id}/payout", settlementId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // --- 중개사 본인 조회: PAID, payout 8000 ---
        mockMvc.perform(get("/api/me/settlements").param("month", MONTH).header("Authorization", "Bearer " + realtorTk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PAID"))
                .andExpect(jsonPath("$.data[0].payoutAmount").value(8000))
                .andExpect(jsonPath("$.data[0].realtorId").value(realtorId));
    }

    private String userToken(String email) {
        User u = userRepository.save(User.create(email, "pw", "u", Role.USER));
        return jwt.createAccessToken(u.getId(), Role.USER);
    }

    private String adminToken(String email) {
        User u = userRepository.save(User.create(email, "pw", "a", Role.ADMIN));
        return jwt.createAccessToken(u.getId(), Role.ADMIN);
    }

    /** 예약권 진입 → 예약 생성 → 결제 확정, paymentId 반환. */
    private long reserveConfirm(long slot, String token) throws Exception {
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long paymentId = objectMapper.readTree(body).path("data").path("paymentId").asLong();
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return paymentId;
    }

    private long firstReservationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/me/reservations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").get(0).path("reservationId").asLong();
    }
}
