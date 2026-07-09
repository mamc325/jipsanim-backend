package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;

import java.math.BigDecimal;
import java.util.List;

public record PropertyDetailResponse(
        Long propertyId,
        Long realtorId,
        String title,
        String description,
        String roadAddress,
        String bjdongCode,
        String sigunguCode,
        String regionName,
        String nearStation,
        PropertyType propertyType,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        PropertyStatus status,
        VerificationStatus verificationStatus,
        RiskLevel riskLevel,
        List<Image> images) {

    public record Image(String imageUrl, boolean primary, int sortOrder) {
    }

    public static PropertyDetailResponse from(Property p) {
        List<Image> images = p.getImages().stream()
                .map(i -> new Image(i.getImageUrl(), i.isPrimary(), i.getSortOrder()))
                .toList();
        return new PropertyDetailResponse(
                p.getId(), p.getRealtor().getId(), p.getTitle(), p.getDescription(), p.getRoadAddress(),
                p.getBjdongCode(), p.getSigunguCode(), p.getRegionName(), p.getNearStation(),
                p.getPropertyType(), p.getDealType(), p.getDeposit(), p.getMonthlyRent(), p.getArea(),
                p.getRoomCount(), p.getStatus(), p.getVerificationStatus(), p.getRiskLevel(), images);
    }
}
