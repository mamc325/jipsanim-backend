package com.jipsanim.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * ES 리포지토리 활성화. `search.elasticsearch.enabled=true` 일 때만 @EnableElasticsearchRepositories 를 켜서
 * 일반 테스트(enabled=false)에서 ES 리포지토리/연결 빈이 뜨지 않게 한다(리뷰 P1).
 */
@Configuration
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
@EnableElasticsearchRepositories(basePackages = "com.jipsanim.search.repository")
public class ElasticsearchRepositoryConfig {
}
