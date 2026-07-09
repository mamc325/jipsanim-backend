package com.jipsanim.property.dto;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.dto.PropertyCreateRequest.ImageRequest;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.List;

/** 부분 수정 — 모든 필드 nullable. images 가 non-null 이면 전체 교체. */
public record PropertyUpdateRequest(
        String title,
        String description,
        String roadAddress,
        String bjdongCode,
        String regionName,
        String nearStation,
        DealType dealType,
        Long deposit,
        Long monthlyRent,
        BigDecimal area,
        Integer roomCount,
        @Valid List<ImageRequest> images) {
}
