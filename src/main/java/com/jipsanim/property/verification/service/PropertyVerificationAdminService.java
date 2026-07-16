package com.jipsanim.property.verification.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.pricestandard.dto.PriceStandardSummary;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.dto.VerificationDecisionResponse;
import com.jipsanim.property.verification.dto.VerificationSummaryResponse;
import com.jipsanim.property.verification.repository.PropertyVerificationRepository;
import com.jipsanim.outbox.publisher.OutboxEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 매물 승인/반려 (FR-050). 승인/반려 시 같은 트랜잭션에 Outbox 이벤트 적재(4차, 직접 append).
 * 이미 처리된 건은 멱등 처리(ALREADY_REVIEWED).
 */
@Service
public class PropertyVerificationAdminService {

    private final PropertyVerificationRepository verificationRepository;
    private final PropertyRepository propertyRepository;
    private final PriceStandardRepository priceStandardRepository;
    private final OutboxEventPublisher outbox;
    private final com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder;
    private final com.jipsanim.property.popular.PopularCacheEvictor cacheEvictor;

    public PropertyVerificationAdminService(PropertyVerificationRepository verificationRepository,
                                            PropertyRepository propertyRepository,
                                            PriceStandardRepository priceStandardRepository,
                                            OutboxEventPublisher outbox,
                                            com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder,
                                            com.jipsanim.property.popular.PopularCacheEvictor cacheEvictor) {
        this.verificationRepository = verificationRepository;
        this.propertyRepository = propertyRepository;
        this.priceStandardRepository = priceStandardRepository;
        this.outbox = outbox;
        this.indexRecorder = indexRecorder;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * 관리자 검증 목록 + 매물 정보/시세 기준 병합. propertyId 들은 배치로 Property 조회, 시세 기준은
     * sigunguCode IN 으로 배치 조회 후 (sigungu,type,deal) 로 인덱싱(N+1 금지).
     */
    @Transactional(readOnly = true)
    public Page<VerificationSummaryResponse> list(VerificationStatus status, RiskLevel riskLevel, Pageable pageable) {
        Page<PropertyVerification> page = verificationRepository.search(status, riskLevel, pageable);
        List<Long> propertyIds = page.getContent().stream()
                .map(PropertyVerification::getPropertyId).distinct().toList();
        Map<Long, Property> properties = propertyRepository.findAllById(propertyIds).stream()
                .collect(Collectors.toMap(Property::getId, Function.identity()));

        Set<String> sigunguCodes = properties.values().stream()
                .map(Property::getSigunguCode).collect(Collectors.toSet());
        Map<String, PriceStandard> priceStandards = sigunguCodes.isEmpty() ? Map.of()
                : priceStandardRepository.findByStatusAndSigunguCodeIn(PriceStandardStatus.ACTIVE, sigunguCodes).stream()
                        .collect(Collectors.toMap(
                                ps -> priceKey(ps.getSigunguCode(), ps.getPropertyType(), ps.getDealType()),
                                Function.identity(), (a, b) -> a));

        return page.map(v -> {
            Property p = properties.get(v.getPropertyId());
            PriceStandardSummary ps = p == null ? null : PriceStandardSummary.from(
                    priceStandards.get(priceKey(p.getSigunguCode(), p.getPropertyType(), p.getDealType())));
            return VerificationSummaryResponse.of(v, p, ps);
        });
    }

    private static String priceKey(String sigunguCode, PropertyType type, DealType deal) {
        return sigunguCode + "|" + type + "|" + deal;
    }

    @Transactional
    public VerificationDecisionResponse approve(Long verificationId, Long adminUserId) {
        PropertyVerification verification = findVerification(verificationId);
        verification.approve(adminUserId);
        Property property = findProperty(verification.getPropertyId());
        boolean wasActive = property.getStatus() == com.jipsanim.property.domain.PropertyStatus.ACTIVE;
        property.approve();
        // 기존 승인 알림 유지 + ACTIVE 진입(prev!=ACTIVE)이면 색인 이벤트 추가(리뷰 P1)
        outbox.append("PROPERTY", property.getId(), "PROPERTY_APPROVED",
                "PROPERTY_APPROVED:" + property.getId(),
                Map.of("recipientUserId", property.getRealtor().getUser().getId(), "propertyId", property.getId()));
        if (!wasActive) {
            indexRecorder.recordIndex(property.getId());
        }
        cacheEvictor.evictDetail(property.getId()); // ACTIVE 진입 → 상세 캐시 최신화(afterCommit)
        return decision(verification, property);
    }

    @Transactional
    public VerificationDecisionResponse reject(Long verificationId, Long adminUserId, String reason) {
        PropertyVerification verification = findVerification(verificationId);
        verification.reject(adminUserId, reason);
        Property property = findProperty(verification.getPropertyId());
        boolean wasActive = property.getStatus() == com.jipsanim.property.domain.PropertyStatus.ACTIVE;
        property.reject();
        outbox.append("PROPERTY", property.getId(), "PROPERTY_REJECTED",
                "PROPERTY_REJECTED:" + property.getId(),
                Map.of("recipientUserId", property.getRealtor().getUser().getId(),
                        "propertyId", property.getId(), "reason", reason == null ? "" : reason));
        // ACTIVE 이탈이면 색인 제거(prev==ACTIVE && new!=ACTIVE) + 랭킹/캐시 정리
        if (wasActive) {
            indexRecorder.recordUnindex(property.getId());
            cacheEvictor.evictOnDeactivate(property.getId());
        }
        return decision(verification, property);
    }

    private PropertyVerification findVerification(Long verificationId) {
        return verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Property findProperty(Long propertyId) {
        return propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private VerificationDecisionResponse decision(PropertyVerification verification, Property property) {
        return new VerificationDecisionResponse(verification.getId(), property.getId(),
                property.getStatus(), verification.getStatus());
    }
}
