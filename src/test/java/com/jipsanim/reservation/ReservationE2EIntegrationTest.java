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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2차 세로 슬라이스 E2E: 진입→예약권 발급→예약→결제 확정→슬롯 RESERVED, 슬롯당 1명. (Refs: T270)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationE2EIntegrationTest {

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
        User ru = userRepository.save(User.create("e2e.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
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

    @Test
    @DisplayName("진입→발급→예약→확정→RESERVED, 이후 다른 사용자 진입 불가")
    void fullFlow() throws Exception {
        long slot = openSlot();
        String u1 = userToken("e2e.u1@test.com");

        // 진입 → 즉시 예약권
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u1))
                .andExpect(jsonPath("$.data.tokenGranted").value(true));

        // 예약 생성
        String rbody = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot)
                        .header("Authorization", "Bearer " + u1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long paymentId = objectMapper.readTree(rbody).path("data").path("paymentId").asLong();

        // 결제 확정 → CONFIRMED / slot RESERVED
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.visitSlotStatus").value("RESERVED"));

        // 슬롯 목록에서 RESERVED
        mockMvc.perform(get("/api/properties/{pid}/visit-slots",
                        slotRepository.findById(slot).orElseThrow().getProperty().getId()))
                .andExpect(jsonPath("$.data[?(@.visitSlotId == " + slot + ")].status").value("RESERVED"));

        // 내 예약 CONFIRMED
        mockMvc.perform(get("/api/me/reservations").header("Authorization", "Bearer " + u1))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));

        // 슬롯당 1명: 다른 사용자 진입 → 409(비OPEN)
        String u2 = userToken("e2e.u2@test.com");
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }
}
