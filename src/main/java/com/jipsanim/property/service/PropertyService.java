package com.jipsanim.property.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.dto.PropertyCreateRequest;
import com.jipsanim.property.dto.PropertyDetailResponse;
import com.jipsanim.property.dto.PropertyMutationResponse;
import com.jipsanim.property.dto.PropertyUpdateRequest;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.repository.RealtorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final RealtorRepository realtorRepository;
    private final com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder;

    public PropertyService(PropertyRepository propertyRepository, RealtorRepository realtorRepository,
                           com.jipsanim.search.index.PropertyIndexEventRecorder indexRecorder) {
        this.propertyRepository = propertyRepository;
        this.realtorRepository = realtorRepository;
        this.indexRecorder = indexRecorder;
    }

    @Transactional
    public PropertyMutationResponse create(Long userId, PropertyCreateRequest req) {
        Realtor realtor = currentRealtor(userId);
        Property property = Property.createDraft(realtor, req.title(), req.description(), req.roadAddress(),
                req.bjdongCode(), req.regionName(), req.nearStation(), req.propertyType(), req.dealType(),
                req.deposit(), req.monthlyRent(), req.area(), req.roomCount());
        applyImages(property, req.images());
        propertyRepository.save(property);
        return PropertyMutationResponse.from(property);
    }

    @Transactional
    public PropertyMutationResponse update(Long userId, Long propertyId, PropertyUpdateRequest req) {
        Property property = findOwned(userId, propertyId);
        if (!property.getStatus().isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "DRAFT/PENDING 상태에서만 수정할 수 있습니다.");
        }
        property.update(req.title(), req.description(), req.roadAddress(), req.bjdongCode(), req.regionName(),
                req.nearStation(), req.dealType(), req.deposit(), req.monthlyRent(), req.area(), req.roomCount());
        if (req.images() != null) {
            property.replaceImages();
            applyImages(property, req.images());
        }
        return PropertyMutationResponse.from(property);
    }

    @Transactional
    public void delete(Long userId, Long propertyId) {
        Property property = findOwned(userId, propertyId);
        boolean wasActive = property.getStatus() == com.jipsanim.property.domain.PropertyStatus.ACTIVE;
        property.softDelete();
        if (wasActive) {
            indexRecorder.recordUnindex(property.getId()); // ACTIVE 이탈 → 색인 제거
        }
    }

    @Transactional(readOnly = true)
    public PropertyDetailResponse getDetail(AuthUser authUser, Long propertyId) {
        Property property = propertyRepository.findWithImagesById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (property.getStatus() == PropertyStatus.DELETED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (property.getStatus() == PropertyStatus.ACTIVE) {
            return PropertyDetailResponse.from(property);
        }
        // 비공개(DRAFT/PENDING/REJECTED/CLOSED/HIDDEN)는 소유자 또는 ADMIN 만
        if (authUser != null && (authUser.role() == Role.ADMIN || isOwner(authUser.userId(), property))) {
            return PropertyDetailResponse.from(property);
        }
        throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    private void applyImages(Property property, List<PropertyCreateRequest.ImageRequest> images) {
        if (images == null) {
            return;
        }
        for (int i = 0; i < images.size(); i++) {
            PropertyCreateRequest.ImageRequest image = images.get(i);
            property.addImage(image.imageUrl(), image.isPrimary(), i);
        }
    }

    private Property findOwned(Long userId, Long propertyId) {
        Property property = propertyRepository.findWithImagesById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Realtor realtor = currentRealtor(userId);
        if (!property.isOwnedBy(realtor.getId())) {
            throw new BusinessException(ErrorCode.NOT_OWNER);
        }
        return property;
    }

    private boolean isOwner(Long userId, Property property) {
        return realtorRepository.findByUserId(userId)
                .map(realtor -> property.isOwnedBy(realtor.getId()))
                .orElse(false);
    }

    private Realtor currentRealtor(Long userId) {
        return realtorRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "중개사만 수행할 수 있습니다."));
    }
}
