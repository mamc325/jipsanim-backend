package com.jipsanim.reservation;

import com.fasterxml.jackson.databind.JsonNode;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationIntegrationTest {

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
        User realtorUser = userRepository.save(User.create("resv.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(realtorUser, "공인", "010"));
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

    private void enterQueue(long slot, String token) throws Exception {
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("예약권 보유자 예약 생성(PENDING+Payment READY) + 멱등 재요청")
    void createAndIdempotent() throws Exception {
        long slot = openSlot();
        String u1 = userToken("resv.u1@test.com");
        enterQueue(slot, u1); // 토큰 발급

        String body = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot)
                        .header("Authorization", "Bearer " + u1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.amount").value(10000))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        long reservationId = objectMapper.readTree(body).path("data").path("reservationId").asLong();

        // 멱등: 같은 사용자 재요청 → 동일 예약 반환
        mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot).header("Authorization", "Bearer " + u1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reservationId").value(reservationId));

        // 내 예약 목록
        mockMvc.perform(get("/api/me/reservations").header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reservationId").value(reservationId))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("예약권 없는 사용자는 예약 불가(403)")
    void noToken() throws Exception {
        long slot = openSlot();
        String u = userToken("resv.notoken@test.com"); // 큐 진입 안 함 → 토큰 없음

        mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot).header("Authorization", "Bearer " + u))
                .andExpect(status().isForbidden());
    }
}
