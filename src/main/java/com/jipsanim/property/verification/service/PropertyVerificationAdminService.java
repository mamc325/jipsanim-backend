package com.jipsanim.property.verification.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.dto.VerificationDecisionResponse;
import com.jipsanim.property.verification.repository.PropertyVerificationRepository;
import com.jipsanim.outbox.publisher.OutboxEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 관리자 매물 승인/반려 (FR-050). 승인/반려 시 같은 트랜잭션에 Outbox 이벤트 적재(4차, 직접 append).
 * 이미 처리된 건은 멱등 처리(ALREADY_REVIEWED).
 */
@Service
public class PropertyVerificationAdminService {

    private final PropertyVerificationRepository verificationRepository;
    private final PropertyRepository propertyRepository;
    private final OutboxEventPublisher outbox;
    private final com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder;
    private final com.jipsanim.property.popular.PopularCacheEvictor cacheEvictor;

    public PropertyVerificationAdminService(PropertyVerificationRepository verificationRepository,
                                            PropertyRepository propertyRepository,
                                            OutboxEventPublisher outbox,
                                            com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder,
                                            com.jipsanim.property.popular.PopularCacheEvictor cacheEvictor) {
        this.verificationRepository = verificationRepository;
        this.propertyRepository = propertyRepository;
        this.outbox = outbox;
        this.indexRecorder = indexRecorder;
        this.cacheEvictor = cacheEvictor;
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
