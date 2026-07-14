package com.jipsanim.notification;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.notification.domain.Notification;
import com.jipsanim.notification.domain.NotificationType;
import com.jipsanim.notification.repository.NotificationRepository;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4차 Phase 4: /me/notifications 조회 + 읽음 + 비소유자 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    JwtTokenProvider jwt;

    private static final AtomicLong SEQ = new AtomicLong(70_000);

    private long user(String email) {
        return userRepository.save(User.create(email, "pw", "n", Role.USER)).getId();
    }

    private Notification seed(long recipientUserId) {
        long outboxId = SEQ.incrementAndGet();
        return notificationRepository.save(Notification.create(recipientUserId,
                NotificationType.VISIT_RESERVATION_CONFIRMED, "예약이 확정되었습니다", "본문", outboxId));
    }

    @Test
    @DisplayName("본인 알림 조회 + unread 필터")
    void listMine() throws Exception {
        long uid = user("noti.a@test.com");
        String token = jwt.createAccessToken(uid, Role.USER);
        Notification read = seed(uid);
        read.markRead();
        notificationRepository.save(read);
        seed(uid); // 미읽음 1

        mockMvc.perform(get("/api/me/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));

        mockMvc.perform(get("/api/me/notifications").param("unread", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].isRead").value(false));
    }

    @Test
    @DisplayName("읽음 처리 PATCH → isRead true")
    void markRead() throws Exception {
        long uid = user("noti.b@test.com");
        String token = jwt.createAccessToken(uid, Role.USER);
        long id = seed(uid).getId();

        mockMvc.perform(patch("/api/notifications/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRead").value(true));
    }

    @Test
    @DisplayName("비소유자 읽음 시도 → 403")
    void markReadNotOwner() throws Exception {
        long owner = user("noti.owner@test.com");
        long id = seed(owner).getId();
        String other = jwt.createAccessToken(user("noti.other@test.com"), Role.USER);

        mockMvc.perform(patch("/api/notifications/{id}", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }
}
