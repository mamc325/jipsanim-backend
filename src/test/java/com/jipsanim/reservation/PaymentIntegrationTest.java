package com.jipsanim.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentIntegrationTest {

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
    JwtTokenProvider jwt;
    @Autowired
    StringRedisTemplate redis;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private Long openSlot() {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("pay.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property p = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve();
        propertyRepository.save(p);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        return slotRepository.save(VisitSlot.create(p, start, start.plusMinutes(30))).getId();
    }

    private String userToken(String email) {
        User u = userRepository.save(User.create(email, "pw", "u", Role.USER));
        return jwt.createAccessToken(u.getId(), Role.USER);
    }

    /** 큐 진입(토큰) → 예약 생성 → paymentId 반환 */
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
    @DisplayName("결제 확정 → CONFIRMED/RESERVED, 멱등 재확정 200, 확정 후 실패 409")
    void confirm() throws Exception {
        long slot = openSlot();
        String u = userToken("pay.confirm@test.com");
        long paymentId = reserve(slot, u);

        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.data.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.visitSlotStatus").value("RESERVED"));

        // 멱등 재확정
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CONFIRMED"));

        // 확정된 결제 실패 시도 → 409
        mockMvc.perform(post("/api/payments/{id}/failure", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("다른 사용자는 확정 불가(403)")
    void confirmNotOwner() throws Exception {
        long slot = openSlot();
        String owner = userToken("pay.owner@test.com");
        long paymentId = reserve(slot, owner);
        String other = userToken("pay.other@test.com");

        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("결제 실패 → FAILED/EXPIRED, 멱등 재실패 200, 실패 후 확정 409")
    void fail() throws Exception {
        long slot = openSlot();
        String u = userToken("pay.fail@test.com");
        long paymentId = reserve(slot, u);

        mockMvc.perform(post("/api/payments/{id}/failure", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.reservationStatus").value("EXPIRED"));

        // 멱등 재실패
        mockMvc.perform(post("/api/payments/{id}/failure", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"));

        // 실패한 결제 확정 시도 → 409(READY 아님)
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isConflict());
    }
}
