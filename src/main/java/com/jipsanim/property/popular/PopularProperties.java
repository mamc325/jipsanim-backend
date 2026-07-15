package com.jipsanim.property.popular;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인기 랭킹/캐시 설정(6차). overfetch=ZSET over-fetch 크기, max=캐시 상위 개수,
 * list/detail TTL(초), decay factor·epsilon(트렌딩 일 감쇠).
 */
@ConfigurationProperties(prefix = "popular")
public record PopularProperties(
        int overfetch, int max, long listTtlSeconds, long detailTtlSeconds,
        double decayFactor, double decayEpsilon) {

    public PopularProperties {
        if (overfetch <= 0) {
            overfetch = 200;
        }
        if (max <= 0) {
            max = 50;
        }
        if (listTtlSeconds <= 0) {
            listTtlSeconds = 60;
        }
        if (detailTtlSeconds <= 0) {
            detailTtlSeconds = 300;
        }
        if (decayFactor <= 0) {
            decayFactor = 0.5;
        }
        if (decayEpsilon <= 0) {
            decayEpsilon = 1.0;
        }
    }
}
