package com.jipsanim.property.verification;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PropertyVerificationIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    private static final String CREATE_BODY = """
            {"title":"역삼 오피스텔","description":"채광이 좋고 역세권에 위치한 풀옵션 오피스텔입니다. 관리 상태 우수합니다.",
             "roadAddress":"서울특별시 강남구 테헤란로 101","bjdongCode":"1168010100","regionName":"서울특별시 강남구 역삼동",
             "nearStation":"강남역","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","deposit":10000000,
             "monthlyRent":700000,"area":33.0,"roomCount":1,"images":[{"imageUrl":"https://img/1.jpg","isPrimary":true}]}
            """;

    @Test
    @DisplayName("검증 요청→REVIEW_REQUIRED→관리자 승인→ACTIVE, 재승인은 409")
    void submitAndApprove() throws Exception {
        String realtorToken = realtorToken("verify.realtor@test.com");
        long propertyId = createProperty(realtorToken);

        // 기준 없음 → REVIEW_REQUIRED
        mockMvc.perform(post("/api/properties/{id}/submission", propertyId)
                        .header("Authorization", "Bearer " + realtorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.verificationStatus").value("REVIEW_REQUIRED"));

        String adminToken = adminToken("verify.admin@test.com");
        long verificationId = findVerificationId(adminToken, propertyId);

        // 승인 → ACTIVE
        mockMvc.perform(post("/api/admin/property-verifications/{id}/approval", verificationId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.propertyStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.verificationStatus").value("APPROVED"));

        // 이제 공개 상세 조회 가능
        mockMvc.perform(get("/api/properties/{id}", propertyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 재승인 → 멱등(409)
        mockMvc.perform(post("/api/admin/property-verifications/{id}/approval", verificationId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_REVIEWED"));
    }

    @Test
    @DisplayName("관리자 반려 시 매물 REJECTED")
    void reject() throws Exception {
        String realtorToken = realtorToken("verify.realtor2@test.com");
        long propertyId = createProperty(realtorToken);
        mockMvc.perform(post("/api/properties/{id}/submission", propertyId)
                .header("Authorization", "Bearer " + realtorToken)).andExpect(status().isOk());

        String adminToken = adminToken("verify.admin2@test.com");
        long verificationId = findVerificationId(adminToken, propertyId);

        mockMvc.perform(post("/api/admin/property-verifications/{id}/rejection", verificationId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"시세 대비 가격 의심"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.propertyStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"));
    }

    private long findVerificationId(String adminToken, long propertyId) throws Exception {
        String body = mockMvc.perform(get("/api/admin/property-verifications?size=100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(body).path("data").path("content")) {
            if (node.path("propertyId").asLong() == propertyId) {
                return node.path("verificationId").asLong();
            }
        }
        throw new IllegalStateException("verification not found for property " + propertyId);
    }

    private long createProperty(String token) throws Exception {
        String body = mockMvc.perform(post("/api/properties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("propertyId").asLong();
    }

    private String realtorToken(String email) throws Exception {
        return signupAndLogin("""
                {"email":"%s","password":"password1","nickname":"중개사","role":"REALTOR",
                 "businessName":"검증공인","phone":"010-1111-2222"}
                """.formatted(email), email);
    }

    private String adminToken(String email) throws Exception {
        return signupAndLogin("""
                {"email":"%s","password":"password1","nickname":"관리자","role":"ADMIN"}
                """.formatted(email), email);
    }

    private String signupAndLogin(String signupBody, String email) throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
        String loginBody = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginBody).path("data").path("accessToken").asText();
    }
}
