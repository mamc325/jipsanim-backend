package com.jipsanim.property.popular;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 6차 인기/캐시 설정 등록. */
@Configuration
@EnableConfigurationProperties(PopularProperties.class)
public class PopularConfig {
}
