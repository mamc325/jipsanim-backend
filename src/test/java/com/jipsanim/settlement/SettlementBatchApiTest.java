package com.jipsanim.settlement;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3차 Phase 3: 정산 배치 수동 실행 API(T324) — 동기 200 + 권한(ADMIN).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementBatchApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JwtTokenProvider jwt;

    private String token(String email, Role role) {
        User u = userRepository.save(User.create(email, "pw", "n", role));
        return jwt.createAccessToken(u.getId(), role);
    }

    @Test
    @DisplayName("ADMIN 배치 실행 → 동기 200 + 결과 카운트")
    void adminRunsBatch() throws Exception {
        String admin = token("settle.admin@test.com", Role.ADMIN);

        mockMvc.perform(post("/api/admin/settlement-batch-jobs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2019-01\"}")) // 데이터 없는 과거 월
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value("2019-01"))
                .andExpect(jsonPath("$.data.createdCount").value(0));
    }

    @Test
    @DisplayName("USER 는 정산 배치 실행 불가 → 403")
    void userForbidden() throws Exception {
        String user = token("settle.user@test.com", Role.USER);

        mockMvc.perform(post("/api/admin/settlement-batch-jobs")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("잘못된 month 형식 → 400")
    void invalidMonth() throws Exception {
        String admin = token("settle.admin2@test.com", Role.ADMIN);

        mockMvc.perform(post("/api/admin/settlement-batch-jobs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2019/01\"}"))
                .andExpect(status().isBadRequest());
    }
}
