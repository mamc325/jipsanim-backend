package com.jipsanim.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 외부 API 호출용 WebClient.Builder 제공. 각 Client 가 baseUrl/timeout 을 붙여 사용한다.
 */
@Configuration
@EnableConfigurationProperties(ExternalApiProperties.class)
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
