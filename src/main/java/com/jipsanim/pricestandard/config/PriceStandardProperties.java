package com.jipsanim.pricestandard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * price-standard.* 설정 (시세 수집 배치·계산 파라미터).
 */
@ConfigurationProperties(prefix = "price-standard")
public record PriceStandardProperties(
        int minSampleCount,
        int collectMonths,
        int webclientConcurrency,
        String calcMethod) {
}
