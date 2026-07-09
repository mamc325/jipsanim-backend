package com.jipsanim.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.pricestandard.candidate.PriceStandardCandidateService;
import com.jipsanim.pricestandard.domain.CalcMethod;
import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP 세로 슬라이스 E2E. (Refs: T070, T071)
 * 1) 매물 등록→검증→관리자 승인→ACTIVE 검색 노출
 * 2) 시세 기준(후보 승인) 반영 후 가격 이상 매물이 PRICE_OUT_OF_RANGE(HIGH)로 판정
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class FullScenarioIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    PriceStandardCandidateRepository candidateRepository;
    @Autowired
    PriceStandardCandidateService candidateService;

    @Test
    @DisplayName("등록→검증→승인→ACTIVE 매물 검색 노출")
    void registerToSearch() throws Exception {
        String realtor = realtorToken("scenario.realtor@test.com");
        long propertyId = createProperty(realtor, "1168010100", 10_000_000L, 700_000L);

        mockMvc.perform(post("/api/properties/{id}/submission", propertyId)
                        .header("Authorization", "Bearer " + realtor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        String admin = adminToken("scenario.admin@test.com");
        long verificationId = findVerificationId(admin, propertyId);
        mockMvc.perform(post("/api/admin/property-verifications/{id}/approval", verificationId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.propertyStatus").value("ACTIVE"));

        // 검색에 노출됨 (sigungu 11680)
        mockMvc.perform(get("/api/properties").param("sigunguCode", "11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("ACTIVE 시세 기준 반영 후 월세 하한 미만 매물은 PRICE_OUT_OF_RANGE(HIGH)")
    void priceRiskAgainstStandard() throws Exception {
        // 후보 생성→승인으로 ACTIVE 기준 반영 (강남 오피스텔 월세: 보증금 5M~50M, 월세 55만~180만)
        PriceStandardCandidate candidate = candidateRepository.save(PriceStandardCandidate.create(
                "11680", "강남구", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                5_000_000L, 50_000_000L, 550_000L, 1_800_000L, CalcMethod.IQR, 100,
                DataStatus.SUFFICIENT, "MOLIT_OFFICETEL_RENT", "2026-07", 1L));
        candidateService.approve(candidate.getId(), 1L);

        String realtor = realtorToken("scenario.price@test.com");
        // 월세 20만 (하한 55만 미만) → PRICE_OUT_OF_RANGE
        long propertyId = createProperty(realtor, "1168010100", 10_000_000L, 200_000L);

        mockMvc.perform(post("/api/properties/{id}/submission", propertyId)
                        .header("Authorization", "Bearer " + realtor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.reasons[*].reasonType",
                        org.hamcrest.Matchers.hasItem("PRICE_OUT_OF_RANGE")));
    }

    private long createProperty(String token, String bjdongCode, long deposit, long monthlyRent) throws Exception {
        String body = """
                {"title":"강남 오피스텔","description":"채광 좋고 역세권 풀옵션 오피스텔입니다. 관리 우수.",
                 "roadAddress":"서울특별시 강남구 테헤란로 101","bjdongCode":"%s","regionName":"서울특별시 강남구 역삼동",
                 "nearStation":"강남역","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","deposit":%d,
                 "monthlyRent":%d,"area":33.0,"roomCount":1,"images":[{"imageUrl":"https://img/1.jpg","isPrimary":true}]}
                """.formatted(bjdongCode, deposit, monthlyRent);
        String res = mockMvc.perform(post("/api/properties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).path("data").path("propertyId").asLong();
    }

    private long findVerificationId(String adminToken, long propertyId) throws Exception {
        String body = mockMvc.perform(get("/api/admin/property-verifications?size=100")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(body).path("data").path("content")) {
            if (node.path("propertyId").asLong() == propertyId) {
                return node.path("verificationId").asLong();
            }
        }
        throw new IllegalStateException("verification not found");
    }

    private String realtorToken(String email) throws Exception {
        return signupAndLogin("""
                {"email":"%s","password":"password1","nickname":"중개사","role":"REALTOR",
                 "businessName":"공인","phone":"010-1111-2222"}
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
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginBody).path("data").path("accessToken").asText();
    }
}
