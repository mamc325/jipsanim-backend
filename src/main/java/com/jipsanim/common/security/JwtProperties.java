package com.jipsanim.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jwt.* 설정 바인딩.
 * secret 은 Base64 인코딩된 값(디코드 후 HS256 키로 사용, 최소 32바이트).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long accessTokenValiditySeconds) {
}
