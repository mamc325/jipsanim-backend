package com.jipsanim.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.outbox.repository.OutboxEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4차 Phase 4: 도메인 흐름이 같은 커밋에 Outbox 이벤트를 적재하는지 검증(worker 는 테스트 중 비활성).
 * 대기(예약권 발급)→예약→결제 확정 흐름에서 WAITING_QUEUE_INVITATION_GRANTED + VISIT_RESERVATION_CONFIRMED 적재.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DomainAppendIntegrationTest {

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
    OutboxEventRepository outboxRepository;
    @Autowired
    JwtTokenProvider jwt;
    @Autowired
    StringRedisTemplate redis;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private long openSlot() {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("out.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
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
    @DisplayName("대기→예약→확정: WAITING_QUEUE_INVITATION_GRANTED + VISIT_RESERVATION_CONFIRMED 적재")
    void appendsOnReservationFlow() throws Exception {
        long slot = openSlot();
        String u = userToken("out.user" + SEQ.get() + "@test.com");

        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + u))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot)
                        .header("Authorization", "Bearer " + u))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long paymentId = objectMapper.readTree(body).path("data").path("paymentId").asLong();
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + u))
                .andExpect(status().isOk());

        long reservationId = reservationId(u);

        assertThat(outboxRepository.countByEventTypeAndAggregateId("WAITING_QUEUE_INVITATION_GRANTED", slot))
                .isGreaterThanOrEqualTo(1);
        assertThat(outboxRepository.countByEventTypeAndAggregateId("VISIT_RESERVATION_CONFIRMED", reservationId))
                .isEqualTo(1);
    }

    private long reservationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/me/reservations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").get(0).path("reservationId").asLong();
    }
}
