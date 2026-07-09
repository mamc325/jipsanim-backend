package com.jipsanim.property.verification.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.verification.domain.PropertyVerification;
import com.jipsanim.property.verification.dto.SubmissionResponse;
import com.jipsanim.property.verification.engine.PropertyVerificationEngine;
import com.jipsanim.property.verification.engine.VerificationResult;
import com.jipsanim.property.verification.repository.PropertyVerificationRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.repository.RealtorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매물 검증 요청(submit): 소유자·DRAFT 확인 → 자동 검증 → Verification/사유 저장 → PENDING 전이 (FR-022).
 */
@Service
public class PropertyVerificationService {

    private final PropertyRepository propertyRepository;
    private final RealtorRepository realtorRepository;
    private final PropertyVerificationEngine verificationEngine;
    private final PropertyVerificationRepository verificationRepository;

    public PropertyVerificationService(PropertyRepository propertyRepository, RealtorRepository realtorRepository,
                                       PropertyVerificationEngine verificationEngine,
                                       PropertyVerificationRepository verificationRepository) {
        this.propertyRepository = propertyRepository;
        this.realtorRepository = realtorRepository;
        this.verificationEngine = verificationEngine;
        this.verificationRepository = verificationRepository;
    }

    @Transactional
    public SubmissionResponse submit(Long userId, Long propertyId) {
        Property property = propertyRepository.findWithImagesById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Realtor realtor = realtorRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "중개사만 수행할 수 있습니다."));
        if (!property.isOwnedBy(realtor.getId())) {
            throw new BusinessException(ErrorCode.NOT_OWNER);
        }
        if (!property.isDraft()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "DRAFT 상태에서만 검증 요청할 수 있습니다.");
        }

        VerificationResult result = verificationEngine.verify(property);

        PropertyVerification verification =
                PropertyVerification.create(propertyId, userId, result.status(), result.riskLevel());
        result.findings().forEach(f -> verification.addReason(f.reasonType(), f.message()));
        verificationRepository.save(verification);

        property.submit(result.status(), result.riskLevel());
        return SubmissionResponse.of(property, result);
    }
}
