package com.jipsanim.property.verification.engine;

import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.property.domain.Property;

import java.util.Optional;

/** 규칙 평가에 필요한 사전 조회 결과를 담은 컨텍스트 (규칙은 무상태 순수 평가). */
public record VerificationContext(
        Property property,
        Optional<PriceStandard> activeStandard,
        boolean duplicateSuspected) {
}
