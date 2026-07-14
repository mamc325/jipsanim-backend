package com.jipsanim.search;

import com.jipsanim.outbox.worker.OutboxPoller;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.service.PropertyService;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.repository.PropertyVerificationRepository;
import com.jipsanim.property.verification.service.PropertyVerificationAdminService;
import com.jipsanim.search.document.PropertyDocument;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5차 Phase 5(T540) E2E: 매물 승인 → Outbox → Worker 색인 → GET /api/properties/search 노출
 * → 삭제(soft delete=비활성) → UNINDEX → 검색 제외. 실제 nori 컨테이너 + HTTP 엔드포인트 전 구간.
 */
@AutoConfigureMockMvc
class PropertySearchE2ETest extends ElasticsearchIntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    PropertyVerificationRepository verificationRepository;
    @Autowired
    PropertyVerificationAdminService adminService;
    @Autowired
    PropertyService propertyService;
    @Autowired
    OutboxPoller poller;
    @Autowired
    ElasticsearchOperations operations;

    @Test
    @DisplayName("승인→색인→검색 노출→삭제→검색 제외 (HTTP 전 구간)")
    void approveIndexSearchDeleteExclude() throws Exception {
        User ru = userRepository.save(User.create("e2e.realtor@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property property = Property.createDraft(realtor, "선릉역 초역세권 신축 오피스텔", "풀옵션 즉시입주 매물입니다",
                "서울 강남구 테헤란로 456", "1168010100", "강남구", "선릉역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, 10_000_000L, 800_000L, new BigDecimal("34"), 1);
        propertyRepository.save(property);
        long propertyId = property.getId();
        PropertyVerification verification = verificationRepository.save(
                PropertyVerification.create(propertyId, ru.getId(), VerificationStatus.PENDING, RiskLevel.LOW));

        // 1) 승인 → PROPERTY_INDEX → Worker 발행 → ES upsert
        adminService.approve(verification.getId(), 999L);
        poller.pollOnce();
        operations.indexOps(PropertyDocument.class).refresh();

        // 2) 검색 노출 확인 (HTTP)
        mockMvc.perform(get("/api/properties/search").param("q", "선릉역 오피스텔"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.propertyId==" + propertyId + ")]").exists());

        // 3) 삭제(soft delete=비활성) → PROPERTY_UNINDEX → Worker → ES 제거
        propertyService.delete(ru.getId(), propertyId);
        poller.pollOnce();
        operations.indexOps(PropertyDocument.class).refresh();

        // 4) 검색 제외 확인 (HTTP)
        mockMvc.perform(get("/api/properties/search").param("q", "선릉역 오피스텔"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.propertyId==" + propertyId + ")]").doesNotExist());
    }
}
