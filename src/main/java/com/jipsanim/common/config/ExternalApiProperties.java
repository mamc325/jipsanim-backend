package com.jipsanim.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * external.* 외부 API 엔드포인트 설정.
 */
@ConfigurationProperties(prefix = "external")
public record ExternalApiProperties(Endpoint address, Endpoint molit) {

    public record Endpoint(String baseUrl, String apiKey, long timeoutMs) {
    }
}
