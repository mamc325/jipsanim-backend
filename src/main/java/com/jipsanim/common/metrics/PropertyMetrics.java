package com.jipsanim.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 6차 관측성 커스텀 메트릭(Micrometer). 코드명은 dot 표기 — Prometheus 노출 시 Counter 에
 * {@code _total} 이 자동 부착된다(예: {@code cache.requests} → {@code cache_requests_total}).
 * 엔드포인트 지연은 actuator 기본 {@code http_server_requests}(templated URI, 저카디널리티)로 커버.
 */
@Component
public class PropertyMetrics {

    private final MeterRegistry registry;

    public PropertyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 캐시 적중. cache=popular|detail */
    public void cacheHit(String cache) {
        registry.counter("cache.requests", "cache", cache, "result", "hit").increment();
    }

    /** 캐시 미스. */
    public void cacheMiss(String cache) {
        registry.counter("cache.requests", "cache", cache, "result", "miss").increment();
    }

    /** Redis 예외로 degrade(캐시 우회). */
    public void cacheError(String cache) {
        registry.counter("cache.errors", "cache", cache).increment();
    }

    /** 중복조회로 미가산. */
    public void dedupSkip() {
        registry.counter("view.dedup.skip").increment();
    }

    /** writeback 배치 1회 + 반영 델타 합. */
    public void flushBatch(long totalDelta) {
        registry.counter("view.flush").increment();
        registry.counter("view.flush.delta").increment(totalDelta);
    }
}
