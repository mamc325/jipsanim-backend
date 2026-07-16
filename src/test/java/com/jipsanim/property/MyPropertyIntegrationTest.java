package com.jipsanim.property;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 중개사 본인 매물 목록 `GET /api/me/properties` (#1). 공개 검색과 달리 DRAFT/PENDING 등 전 상태 노출,
 * 본인 매물만.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyPropertyIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    @DisplayName("본인 매물 목록: DRAFT 매물 노출 + status 필터 + 타 중개사 매물 제외")
    void myProperties() throws Exception {
        String owner = realtorToken();
        createProperty(owner);
        createProperty(owner);
        String other = realtorToken();
        createProperty(other);

        // owner 는 자기 2건만
        mockMvc.perform(get("/api/me/properties").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.content[0].dealType").value("MONTHLY_RENT"))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists());

        // status=PENDING 필터 → DRAFT 만 있으므로 0건
        mockMvc.perform(get("/api/me/properties").param("status", "PENDING")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("USER 권한은 접근 불가(REALTOR 전용)")
    void userForbidden() throws Exception {
        String userToken = userToken();
        mockMvc.perform(get("/api/me/properties").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    private long createProperty(String token) throws Exception {
        int n = SEQ.incrementAndGet();
        String body = """
                {"title":"역삼 오피스텔 %d","description":"채광 좋은 풀옵션 오피스텔","roadAddress":"서울특별시 강남구 테헤란로 %d",
                 "bjdongCode":"1168010100","regionName":"서울특별시 강남구 역삼동","nearStation":"강남역",
                 "propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","deposit":10000000,"monthlyRent":700000,
                 "area":33.0,"roomCount":1,"images":[{"imageUrl":"https://img/1.jpg","isPrimary":true}]}
                """.formatted(n, n);
        String res = mockMvc.perform(post("/api/properties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).path("data").path("propertyId").asLong();
    }

    private String realtorToken() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "myprop.realtor" + n + "@test.com";
        String signup = """
                {"email":"%s","password":"password1","nickname":"중개사","role":"REALTOR",
                 "businessName":"공인%d","phone":"010-1234-5678"}
                """.formatted(email, n);
        return signupAndLogin(signup, email);
    }

    private String userToken() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "myprop.user" + n + "@test.com";
        String signup = """
                {"email":"%s","password":"password1","nickname":"유저","role":"USER"}
                """.formatted(email);
        return signupAndLogin(signup, email);
    }

    private String signupAndLogin(String signupBody, String email) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
        String loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password1\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(loginRes);
        return root.path("data").path("accessToken").asText();
    }
}
