package com.jipsanim.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.settlement.repository.RefundRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3차 Phase 2: 예약 취소/환불 상태전이 + 슬롯 재개방(T310/T313).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CancellationIntegrationTest {

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
    RefundRepository refundRepository;
    @Autowired
    JwtTokenProvider jwt;
    @Autowired
    StringRedisTemplate redis;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    /** startTime 을 지정해 슬롯 생성(24h 경계 테스트용). */
    private Long openSlotAt(LocalDateTime start) {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("cxl.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property p = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve();
        propertyRepository.save(p);
        return slotRepository.save(VisitSlot.create(p, start, start.plusMinutes(30))).getId();
    }

    private String userToken(String email) {
        User u = userRepository.save(User.create(email, "pw", "u", Role.USER));
        return jwt.createAccessToken(u.getId(), Role.USER);
    }

    private long reserve(long slot, String token) throws Exception {
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("paymentId").asLong();
    }

    @Test
    @DisplayName("24h 전 취소 → REFUNDED/CANCELLED/OPEN, 재취소 멱등(환불 1건 유지)")
    void cancelSuccessAndIdempotent() throws Exception {
        long slot = openSlotAt(LocalDateTime.now().plusDays(3).withNano(0));
        String u = userToken("cxl.ok@test.com");
        long paymentId = reserve(slot, u);
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk());
        long reservationId = reservationIdOf(paymentId, u);

        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refundAmount").value(10000))
                .andExpect(jsonPath("$.data.visitSlotStatus").value("OPEN"));

        // 재취소 멱등 → 200 동일, 환불은 1건만
        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.refundAmount").value(10000));

        // payment_id UNIQUE → 재취소해도 이 결제의 환불은 정확히 1건(전역 count 는 다른 테스트 영향받아 지양)
        assertThat(refundRepository.findByPaymentId(paymentId)).isPresent();
    }

    @Test
    @DisplayName("24h 이내 취소 → 409")
    void cancelWithin24hRejected() throws Exception {
        long slot = openSlotAt(LocalDateTime.now().plusHours(1).withNano(0));
        String u = userToken("cxl.late@test.com");
        long paymentId = reserve(slot, u);
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk());
        long reservationId = reservationIdOf(paymentId, u);

        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("비CONFIRMED(PENDING) 취소 → 409")
    void cancelNonConfirmedRejected() throws Exception {
        long slot = openSlotAt(LocalDateTime.now().plusDays(3).withNano(0));
        String u = userToken("cxl.pending@test.com");
        long paymentId = reserve(slot, u); // 확정 안 함 → PENDING_PAYMENT
        long reservationId = reservationIdOf(paymentId, u);

        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("비소유자 취소 → 403")
    void cancelNotOwnerRejected() throws Exception {
        long slot = openSlotAt(LocalDateTime.now().plusDays(3).withNano(0));
        String owner = userToken("cxl.owner@test.com");
        long paymentId = reserve(slot, owner);
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        long reservationId = reservationIdOf(paymentId, owner);

        String other = userToken("cxl.other@test.com");
        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("취소 후 슬롯 재개방 → 다른 사용자 재예약 가능 (T313)")
    void reopenAllowsRebooking() throws Exception {
        long slot = openSlotAt(LocalDateTime.now().plusDays(3).withNano(0));
        String u1 = userToken("cxl.first@test.com");
        long paymentId = reserve(slot, u1);
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk());
        long reservationId = reservationIdOf(paymentId, u1);
        mockMvc.perform(post("/api/reservations/{id}/cancellation", reservationId).header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk());

        // 다른 사용자 재예약
        String u2 = userToken("cxl.second@test.com");
        long paymentId2 = reserve(slot, u2);
        assertThat(paymentId2).isPositive();
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId2).header("Authorization", "Bearer " + u2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visitSlotStatus").value("RESERVED"));
    }

    /** 예약 요약 조회로 paymentId 에 대응하는 reservationId 를 찾는다. */
    private long reservationIdOf(long paymentId, String token) throws Exception {
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/me/reservations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var arr = objectMapper.readTree(body).path("data");
        for (var node : arr) {
            // 요약에는 paymentId 가 없으므로 첫 예약(단일 시나리오) 반환
            return node.path("reservationId").asLong();
        }
        throw new IllegalStateException("no reservation");
    }
}
