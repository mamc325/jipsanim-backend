package com.jipsanim.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import com.jipsanim.outbox.worker.OutboxPoller;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4차 세로 슬라이스 E2E(T440): 예약 확정 → OutboxEvent 적재 → Worker 폴링 발행 →
 * Notification 생성 → /me/notifications 조회 → 읽음. (스케줄러 비활성, pollOnce() 직접 호출)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationE2EIntegrationTest {

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
    OutboxPoller poller;
    @Autowired
    JwtTokenProvider jwt;
    @Autowired
    StringRedisTemplate redis;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("E2E: 확정→Outbox→Worker 발행→알림 조회→읽음")
    void reservationConfirmedNotification() throws Exception {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("noe2e.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property p = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve();
        propertyRepository.save(p);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        long slot = slotRepository.save(VisitSlot.create(p, start, start.plusMinutes(30))).getId();

        User user = userRepository.save(User.create("noe2e.user" + n + "@test.com", "pw", "u", Role.USER));
        String token = jwt.createAccessToken(user.getId(), Role.USER);

        // 대기 → 예약 → 결제 확정 (VISIT_RESERVATION_CONFIRMED 적재)
        mockMvc.perform(post("/api/visit-slots/{id}/waiting", slot).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/visit-slots/{id}/reservations", slot)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long paymentId = objectMapper.readTree(body).path("data").path("paymentId").asLong();
        mockMvc.perform(post("/api/payments/{id}/confirmation", paymentId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        long reservationId = reservationId(token);

        // Worker 폴링 발행(스케줄러 대신 직접 호출)
        poller.pollOnce();

        // Outbox PUBLISHED
        assertThat(outboxRepository.countByEventTypeAndAggregateId("VISIT_RESERVATION_CONFIRMED", reservationId))
                .isEqualTo(1);
        // 사용자 알림 조회 → 예약 확정 알림 존재
        mockMvc.perform(get("/api/me/notifications").param("unread", "true").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.type=='VISIT_RESERVATION_CONFIRMED')]").exists());

        // 읽음 처리
        long notificationId = firstNotificationId(token);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/notifications/{id}", notificationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRead").value(true));
    }

    private long reservationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/me/reservations").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").get(0).path("reservationId").asLong();
    }

    private long firstNotificationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/me/notifications").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("content").get(0).path("notificationId").asLong();
    }
}
