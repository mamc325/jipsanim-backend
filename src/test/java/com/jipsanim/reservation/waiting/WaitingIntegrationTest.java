package com.jipsanim.reservation.waiting;

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
import org.springframework.transaction.annotation.Transactional;

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
class WaitingIntegrationTest {

    @Autowired
    MockMvc mockMvc;
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
        User realtorUser = userRepository.save(User.create("wait.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
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

    @Test
    @DisplayName("진입: 첫 사용자는 즉시 예약권, 둘째는 대기 순번 / 중복진입·재발급 방지")
    void enterAndStatus() throws Exception {
        long slot = openSlot();
        String u1 = userToken("wait.u1@test.com");
        String u2 = userToken("wait.u2@test.com");

        // u1 진입 → 즉시 예약권
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tokenGranted").value(true))
                .andExpect(jsonPath("$.data.position").value(0));

        // u1 재진입 → 이미 예약권 보유 409
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_GRANTED"));

        // u2 진입 → 대기 1번
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tokenGranted").value(false))
                .andExpect(jsonPath("$.data.position").value(1));

        // u2 재진입 → 이미 대기중 409
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_WAITING"));

        // 순번 조회
        mockMvc.perform(get("/api/visit-slots/{id}/waiting/me", slot).header("Authorization", "Bearer " + u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenGranted").value(true))
                .andExpect(jsonPath("$.data.tokenExpiresInSeconds").value(org.hamcrest.Matchers.greaterThan(0)));
        mockMvc.perform(get("/api/visit-slots/{id}/waiting/me", slot).header("Authorization", "Bearer " + u2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(1))
                .andExpect(jsonPath("$.data.tokenGranted").value(false));
    }

    @Test
    @Transactional
    @DisplayName("OPEN 아닌 슬롯 진입은 409")
    void enterClosedSlot() throws Exception {
        long slot = openSlot();
        slotRepository.closeIfOpen(slot); // CLOSED
        String u1 = userToken("wait.closed@test.com");

        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }
}
