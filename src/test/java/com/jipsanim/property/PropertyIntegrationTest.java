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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PropertyIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private static final String CREATE_BODY = """
            {"title":"역삼 오피스텔","description":"채광 좋은 풀옵션 오피스텔","roadAddress":"서울특별시 강남구 테헤란로 101",
             "bjdongCode":"1168010100","regionName":"서울특별시 강남구 역삼동","nearStation":"강남역",
             "propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","deposit":10000000,"monthlyRent":700000,
             "area":33.0,"roomCount":1,"images":[{"imageUrl":"https://img/1.jpg","isPrimary":true}]}
            """;

    @Test
    @DisplayName("중개사: 매물 생성→상세(파생 sigunguCode)→수정→삭제 흐름")
    void realtorCrud() throws Exception {
        String token = realtorToken("realtor.crud@test.com", "역삼공인");

        long propertyId = createProperty(token);

        // 소유자 상세 조회: DRAFT 도 보이고 sigunguCode 파생 확인
        mockMvc.perform(get("/api/properties/{id}", propertyId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sigunguCode").value("11680"))
                .andExpect(jsonPath("$.data.images[0].primary").value(true));

        // 비공개(DRAFT) 매물은 미인증 조회 시 404
        mockMvc.perform(get("/api/properties/{id}", propertyId))
                .andExpect(status().isNotFound());

        // 수정
        mockMvc.perform(patch("/api/properties/{id}", propertyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"역삼 신축 오피스텔","monthlyRent":800000}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/properties/{id}", propertyId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.title").value("역삼 신축 오피스텔"))
                .andExpect(jsonPath("$.data.monthlyRent").value(800000));

        // 삭제(soft) → 이후 조회 404
        mockMvc.perform(delete("/api/properties/{id}", propertyId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/properties/{id}", propertyId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USER 는 매물을 생성할 수 없다(403)")
    void userCannotCreate() throws Exception {
        String userToken = signupAndLogin("""
                {"email":"user.prop@test.com","password":"password1","nickname":"유저","role":"USER"}
                """, "user.prop@test.com");

        mockMvc.perform(post("/api/properties")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 중개사의 매물은 수정할 수 없다(403 NOT_OWNER)")
    void notOwnerCannotUpdate() throws Exception {
        String ownerToken = realtorToken("owner@test.com", "소유공인");
        String otherToken = realtorToken("other@test.com", "타인공인");

        long propertyId = createProperty(ownerToken);

        mockMvc.perform(patch("/api/properties/{id}", propertyId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"가로채기"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_OWNER"));
    }

    private long createProperty(String token) throws Exception {
        String body = mockMvc.perform(post("/api/properties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("propertyId").asLong();
    }

    private String realtorToken(String email, String businessName) throws Exception {
        String signupBody = """
                {"email":"%s","password":"password1","nickname":"중개사","role":"REALTOR",
                 "businessName":"%s","phone":"010-1234-5678"}
                """.formatted(email, businessName);
        return signupAndLogin(signupBody, email);
    }

    private String signupAndLogin(String signupBody, String email) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(loginBody);
        return root.path("data").path("accessToken").asText();
    }
}
