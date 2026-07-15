package com.jipsanim.property.view;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 매물 조회수 카운팅(6차 P1). ACTIVE 공개표현 조회 시 호출.
 * dedup + writeback 델타(HINCRBY) + 트렌딩 랭킹(ZINCRBY)을 단일 Lua 로 원자 실행.
 * best-effort: Redis 장애가 상세 조회에 전파되지 않도록 예외를 삼킨다(원칙 I 유추).
 */
@Service
public class ViewCountService {

    private static final Logger log = LoggerFactory.getLogger(ViewCountService.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> viewCountScript;
    private final long windowSeconds;

    public ViewCountService(StringRedisTemplate redis, RedisScript<Long> viewCountScript,
                            ViewCountProperties properties) {
        this.redis = redis;
        this.viewCountScript = viewCountScript;
        this.windowSeconds = properties.windowSeconds();
    }

    /**
     * 조회수 1 증가(중복조회는 dedup 윈도우로 무시). cache hit/miss 와 무관하게 호출.
     *
     * @return true=집계됨, false=중복조회 skip 또는 Redis 장애(best-effort)
     */
    public boolean record(Long propertyId, String viewerKey) {
        try {
            Long counted = redis.execute(viewCountScript,
                    List.of(ViewCountRedisConfig.dedupKey(propertyId, viewerKey),
                            ViewCountRedisConfig.VIEW_PENDING,
                            ViewCountRedisConfig.PROPERTY_POPULAR),
                    String.valueOf(windowSeconds), String.valueOf(propertyId));
            return counted != null && counted == 1L;
        } catch (Exception e) {
            log.warn("조회수 카운트 실패(best-effort skip) propertyId={}: {}", propertyId, e.getMessage());
            return false;
        }
    }
}
