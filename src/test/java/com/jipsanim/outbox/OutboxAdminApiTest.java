package com.jipsanim.outbox;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4차 Phase 4: /admin/outbox-events 조회 + DEAD 재처리(비DEAD 409) + 권한(USER 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OutboxAdminApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    JwtTokenProvider jwt;

    private static final AtomicLong SEQ = new AtomicLong(80_000);

    private String token(String email, Role role) {
        return jwt.createAccessToken(userRepository.save(User.create(email, "pw", "n", role)).getId(), role);
    }

    private OutboxEvent seed(OutboxStatus status) {
        long n = SEQ.incrementAndGet();
        OutboxEvent e = OutboxEvent.create("RESERVATION", n, "VISIT_RESERVATION_CONFIRMED",
                "VISIT_RESERVATION_CONFIRMED:" + n, "{\"recipientUserId\":1}", LocalDateTime.now());
        if (status != OutboxStatus.PENDING) {
            for (int i = 0; i < 6 && status == OutboxStatus.DEAD; i++) {
                e.markFailed(LocalDateTime.now(), "boom");
            }
        }
        return outboxRepository.saveAndFlush(e);
    }

    @Test
    @DisplayName("ADMIN 조회 status 필터")
    void listByStatus() throws Exception {
        String admin = token("ob.admin1@test.com", Role.ADMIN);
        seed(OutboxStatus.DEAD);

        mockMvc.perform(get("/api/admin/outbox-events").param("status", "DEAD")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD"));
    }

    @Test
    @DisplayName("DEAD 재처리 → PENDING, attempts 0")
    void reprocessDead() throws Exception {
        String admin = token("ob.admin2@test.com", Role.ADMIN);
        long id = seed(OutboxStatus.DEAD).getId();

        mockMvc.perform(post("/api/admin/outbox-events/{id}/reprocess", id)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attempts").value(0));
    }

    @Test
    @DisplayName("DEAD 아닌 이벤트 재처리 → 409")
    void reprocessNonDead() throws Exception {
        String admin = token("ob.admin3@test.com", Role.ADMIN);
        long id = seed(OutboxStatus.PENDING).getId();

        mockMvc.perform(post("/api/admin/outbox-events/{id}/reprocess", id)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("USER 는 Outbox 관리 불가 → 403")
    void userForbidden() throws Exception {
        String user = token("ob.user@test.com", Role.USER);

        mockMvc.perform(get("/api/admin/outbox-events").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());
    }
}
