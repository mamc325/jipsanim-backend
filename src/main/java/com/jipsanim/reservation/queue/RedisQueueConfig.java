package com.jipsanim.reservation.queue;

import com.jipsanim.reservation.config.ReservationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 대기열/예약권 Lua 스크립트 빈. (원자적 진입/발급/소유자 삭제)
 */
@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class RedisQueueConfig {

    @Bean
    public RedisScript<Long> enqueueScript() {
        return RedisScript.of(new ClassPathResource("lua/enqueue.lua"), Long.class);
    }

    @Bean
    public RedisScript<String> tryIssueScript() {
        return RedisScript.of(new ClassPathResource("lua/try_issue.lua"), String.class);
    }

    @Bean
    public RedisScript<Long> releaseTokenIfOwnerScript() {
        return RedisScript.of(new ClassPathResource("lua/release_token_if_owner.lua"), Long.class);
    }
}
