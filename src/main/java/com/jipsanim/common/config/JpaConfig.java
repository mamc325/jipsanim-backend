package com.jipsanim.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 (BaseTimeEntity 의 createdAt/updatedAt 자동 채움).
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
