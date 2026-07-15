package com.jipsanim.property.view;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 조회수 Redis 키/스크립트 빈(6차). 카운트는 원자 Lua 하나로 dedup+델타+랭킹.
 */
@Configuration
@EnableConfigurationProperties(ViewCountProperties.class)
public class ViewCountRedisConfig {

    /** writeback 대기 델타 hash. */
    public static final String VIEW_PENDING = "view:pending";
    /** writeback 배출 중 스냅샷(고정 키). */
    public static final String VIEW_FLUSHING = "view:flushing";
    /** 트렌딩 랭킹 zset. */
    public static final String PROPERTY_POPULAR = "property:popular";

    public static String dedupKey(Long propertyId, String viewerKey) {
        return "view:dedup:" + propertyId + ":" + viewerKey;
    }

    @Bean
    public RedisScript<Long> viewCountScript() {
        return RedisScript.of(new ClassPathResource("lua/view_count.lua"), Long.class);
    }
}
