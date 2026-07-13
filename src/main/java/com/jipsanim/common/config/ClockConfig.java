package com.jipsanim.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시간 의존 로직(취소 24h 판정, 정산 월 계산)에서 주입 가능한 Clock 제공.
 * 테스트에서 고정 Clock 으로 대체해 시간 경계를 안정적으로 검증한다(리뷰 P1).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
