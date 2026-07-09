package com.jipsanim.property.verification.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.dto.VerificationDecisionResponse;
import com.jipsanim.property.verification.event.PropertyApprovedEvent;
import com.jipsanim.property.verification.event.PropertyRejectedEvent;
import com.jipsanim.property.verification.repository.PropertyVerificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 매물 승인/반려 (FR-050). 승인 시 ACTIVE 전이 + 도메인 이벤트 발행(4차 Outbox 대비, plan D6).
 * 이미 처리된 건은 멱등 처리(ALREADY_REVIEWED).
 */
@Service
public class PropertyVerificationAdminService {

    private final PropertyVerificationRepository verificationRepository;
    private final PropertyRepository propertyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PropertyVerificationAdminService(PropertyVerificationRepository verificationRepository,
                                            PropertyRepository propertyRepository,
                                            ApplicationEventPublisher eventPublisher) {
        this.verificationRepository = verificationRepository;
        this.propertyRepository = propertyRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public VerificationDecisionResponse approve(Long verificationId, Long adminUserId) {
        PropertyVerification verification = findVerification(verificationId);
        verification.approve(adminUserId);
        Property property = findProperty(verification.getPropertyId());
        property.approve();
        eventPublisher.publishEvent(new PropertyApprovedEvent(property.getId()));
        return decision(verification, property);
    }

    @Transactional
    public VerificationDecisionResponse reject(Long verificationId, Long adminUserId, String reason) {
        PropertyVerification verification = findVerification(verificationId);
        verification.reject(adminUserId, reason);
        Property property = findProperty(verification.getPropertyId());
        property.reject();
        eventPublisher.publishEvent(new PropertyRejectedEvent(property.getId(), reason));
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
