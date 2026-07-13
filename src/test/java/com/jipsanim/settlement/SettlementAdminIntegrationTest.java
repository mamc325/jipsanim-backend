package com.jipsanim.settlement;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.domain.SettlementAmounts;
import com.jipsanim.settlement.repository.SettlementRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3차 Phase 4: 정산 조회/확정/지급 API(T333). 격리 위해 realtorId·month 는 테스트 전용 값 사용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementAdminIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    SettlementRepository settlementRepository;
    @Autowired
    JwtTokenProvider jwt;

    private static final AtomicLong REALTOR_SEQ = new AtomicLong(92_000);

    private String token(String email, Role role) {
        User u = userRepository.save(User.create(email, "pw", "n", role));
        return jwt.createAccessToken(u.getId(), role);
    }

    private final long[] realtorIdHolder = new long[1];

    /** REALTOR 유저+프로필 생성, 토큰 반환(realtorId 는 realtorIdHolder[0]). */
    private String realtorToken(String email) {
        User u = userRepository.save(User.create(email, "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(u, "공인", "010"));
        realtorIdHolder[0] = realtor.getId();
        return jwt.createAccessToken(u.getId(), Role.REALTOR);
    }

    private Settlement saveSettlement(long realtorId, String month) {
        SettlementAmounts a = new SettlementAmounts(500_000L, 50_000L, 450_000L, 0L, 90_000L, 0L, 360_000L);
        return settlementRepository.save(Settlement.create(realtorId, month, a));
    }

    @Test
    @DisplayName("확정→지급 상태전이 + 멱등 재호출 200")
    void confirmAndPayoutLifecycle() throws Exception {
        String admin = token("st.admin1@test.com", Role.ADMIN);
        long id = saveSettlement(REALTOR_SEQ.incrementAndGet(), "2021-01").getId();

        mockMvc.perform(post("/api/admin/settlements/{id}/confirmation", id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        // 멱등 재확정 → 200 CONFIRMED
        mockMvc.perform(post("/api/admin/settlements/{id}/confirmation", id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(post("/api/admin/settlements/{id}/payout", id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        // 멱등 재지급 → 200 PAID
        mockMvc.perform(post("/api/admin/settlements/{id}/payout", id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    @DisplayName("PENDING 지급 시도 → 409")
    void payoutPendingRejected() throws Exception {
        String admin = token("st.admin2@test.com", Role.ADMIN);
        long id = saveSettlement(REALTOR_SEQ.incrementAndGet(), "2021-02").getId();

        mockMvc.perform(post("/api/admin/settlements/{id}/payout", id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("/me/settlements: 중개사 본인 정산 조회(month 필터, realtorId 포함)")
    void mySettlements() throws Exception {
        String token = realtorToken("st.realtor1@test.com");
        long realtorId = realtorIdHolder[0];
        saveSettlement(realtorId, "2021-03");
        saveSettlement(realtorId, "2021-04");

        mockMvc.perform(get("/api/me/settlements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/me/settlements").param("month", "2021-03")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].settlementMonth").value("2021-03"))
                .andExpect(jsonPath("$.data[0].realtorId").value(realtorId));
    }

    @Test
    @DisplayName("/admin/settlements: realtorId 필터")
    void adminListFilter() throws Exception {
        String admin = token("st.admin3@test.com", Role.ADMIN);
        long r1 = REALTOR_SEQ.incrementAndGet();
        long r2 = REALTOR_SEQ.incrementAndGet();
        saveSettlement(r1, "2021-05");
        saveSettlement(r2, "2021-05");

        mockMvc.perform(get("/api/admin/settlements").param("realtorId", String.valueOf(r1))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].realtorId").value(r1));
    }

    @Test
    @DisplayName("USER 는 /me/settlements 접근 불가 → 403")
    void userForbiddenOnMySettlements() throws Exception {
        String user = token("st.user1@test.com", Role.USER);

        mockMvc.perform(get("/api/me/settlements").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());
    }
}
