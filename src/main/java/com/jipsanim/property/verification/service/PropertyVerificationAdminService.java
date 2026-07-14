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

    public PropertyVerificationAdminService(PropertyVerificationRepository verificationRepository,
                                            PropertyRepository propertyRepository,
                                            OutboxEventPublisher outbox) {
        this.verificationRepository = verificationRepository;
        this.propertyRepository = propertyRepository;
        this.outbox = outbox;
    }

    @Transactional
    public VerificationDecisionResponse approve(Long verificationId, Long adminUserId) {
        PropertyVerification verification = findVerification(verificationId);
        verification.approve(adminUserId);
        Property property = findProperty(verification.getPropertyId());
        property.approve();
        outbox.append("PROPERTY", property.getId(), "PROPERTY_APPROVED",
                "PROPERTY_APPROVED:" + property.getId(),
                Map.of("recipientUserId", property.getRealtor().getUser().getId(), "propertyId", property.getId()));
        return decision(verification, property);
    }

    @Transactional
    public VerificationDecisionResponse reject(Long verificationId, Long adminUserId, String reason) {
        PropertyVerification verification = findVerification(verificationId);
        verification.reject(adminUserId, reason);
        Property property = findProperty(verification.getPropertyId());
        property.reject();
        outbox.append("PROPERTY", property.getId(), "PROPERTY_REJECTED",
                "PROPERTY_REJECTED:" + property.getId(),
                Map.of("recipientUserId", property.getRealtor().getUser().getId(),
                        "propertyId", property.getId(), "reason", reason == null ? "" : reason));
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
