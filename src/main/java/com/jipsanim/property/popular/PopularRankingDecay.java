package com.jipsanim.property.popular;

import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 트렌딩 랭킹 일 감쇠(6차). 전체 score 에 factor(기본 0.5)를 곱해(=ZUNIONSTORE WEIGHTS, 단일 원자)
 * 오래된 인기의 영구 고착을 막고, 임계(epsilon) 이하 member 를 제거해 집합 크기를 유한하게 유지.
 * 스케줄링은 {@link PopularRankingDecayScheduler}(게이팅)가 담당 — 이 빈은 항상 존재(테스트는 직접 호출).
 */
@Component
public class PopularRankingDecay {

    private final StringRedisTemplate redis;
    private final double factor;
    private final double epsilon;

    public PopularRankingDecay(StringRedisTemplate redis, PopularProperties props) {
        this.redis = redis;
        this.factor = props.decayFactor();
        this.epsilon = props.decayEpsilon();
    }

    public void decay() {
        // ZUNIONSTORE property:popular 1 property:popular WEIGHTS factor — 전체 score × factor (원자)
        redis.opsForZSet().unionAndStore(PropertyCacheKeys.PROPERTY_POPULAR, List.of(),
                PropertyCacheKeys.PROPERTY_POPULAR, Aggregate.SUM, Weights.of(factor));
        // 임계 이하 제거(집합 유한). removeRangeByScore 는 inclusive(<= epsilon).
        redis.opsForZSet().removeRangeByScore(PropertyCacheKeys.PROPERTY_POPULAR,
                Double.NEGATIVE_INFINITY, epsilon);
    }
}
