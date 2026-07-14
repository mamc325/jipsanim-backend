package com.jipsanim.search;

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
import com.jipsanim.outbox.worker.OutboxPoller;
import com.jipsanim.search.repository.PropertyDocumentRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5차 Phase 3(T522): 매물 승인 → Outbox(PROPERTY_INDEX) → Worker → ES 문서 존재,
 * ACTIVE softDelete → PROPERTY_UNINDEX → ES 문서 삭제. (worker 는 테스트 중 비활성 → pollOnce 직접 호출)
 */
class PropertyIndexingIntegrationTest extends ElasticsearchIntegrationTestSupport {

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
    PropertyDocumentRepository documentRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    @DisplayName("승인→색인→ES 노출, ACTIVE 삭제→UNINDEX→ES 제거")
    void indexOnApproveAndUnindexOnDelete() {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("idx.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property property = Property.createDraft(realtor, "강남역 5분 풀옵션 오피스텔", "설명설명설명설명설명설명",
                "서울 강남구 테헤란로 123", "1168010100", "강남구", "강남역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, 10_000_000L, 700_000L, new BigDecimal("33"), 1);
        propertyRepository.save(property);
        long propertyId = property.getId();
        PropertyVerification verification = verificationRepository.save(
                PropertyVerification.create(propertyId, ru.getId(), VerificationStatus.PENDING, RiskLevel.LOW));

        // 승인 → PROPERTY_INDEX 적재 → Worker 발행 → ES upsert
        adminService.approve(verification.getId(), 999L);
        poller.pollOnce();

        var indexed = documentRepository.findById(String.valueOf(propertyId));
        assertThat(indexed).isPresent();
        assertThat(indexed.get().getTitle()).contains("오피스텔");
        assertThat(indexed.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(indexed.get().getRegionName()).isEqualTo("강남구");

        // ACTIVE 매물 삭제 → PROPERTY_UNINDEX → ES 제거
        propertyService.delete(ru.getId(), propertyId);
        poller.pollOnce();

        assertThat(documentRepository.findById(String.valueOf(propertyId))).isEmpty();
    }
}
