package com.jipsanim.property.popular;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.metrics.PropertyMetrics;
import com.jipsanim.property.dto.PropertyDetailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ACTIVE 공개 상세 cache-aside(6차 P1·P4). ACTIVE 공개표현만 저장/조회, 비공개 표현은 저장 금지.
 * 모든 연산 best-effort — Redis 장애가 상세 조회에 전파되지 않는다(degrade → DB 직조회).
 */
@Component
public class PropertyDetailCache {

    private static final Logger log = LoggerFactory.getLogger(PropertyDetailCache.class);

    private static final String CACHE = "detail";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PropertyMetrics metrics;
    private final long ttlSeconds;

    public PropertyDetailCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                               PropertyMetrics metrics, PopularProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.ttlSeconds = props.detailTtlSeconds();
    }

    /** 캐시 조회. hit 이면 응답, miss/장애면 null. */
    public PropertyDetailResponse get(Long propertyId) {
        try {
            String json = redis.opsForValue().get(PropertyCacheKeys.detailKey(propertyId));
            if (json == null) {
                metrics.cacheMiss(CACHE);
                return null;
            }
            metrics.cacheHit(CACHE);
            return objectMapper.readValue(json, PropertyDetailResponse.class);
        } catch (Exception e) {
            log.warn("상세 캐시 조회 실패(degrade) id={}: {}", propertyId, e.getMessage());
            metrics.cacheError(CACHE);
            return null;
        }
    }

    /** ACTIVE 공개표현만 저장(호출측이 게이트). */
    public void put(Long propertyId, PropertyDetailResponse response) {
        try {
            redis.opsForValue().set(PropertyCacheKeys.detailKey(propertyId),
                    objectMapper.writeValueAsString(response), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("상세 캐시 저장 실패(무시) id={}: {}", propertyId, e.getMessage());
        }
    }

    public void evict(Long propertyId) {
        try {
            redis.delete(PropertyCacheKeys.detailKey(propertyId));
        } catch (RuntimeException e) {
            log.warn("상세 캐시 무효화 실패(무시) id={}: {}", propertyId, e.getMessage());
        }
    }
}
