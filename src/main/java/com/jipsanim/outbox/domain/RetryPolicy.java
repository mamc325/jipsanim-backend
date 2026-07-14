package com.jipsanim.outbox.domain;

import java.time.Duration;

/**
 * 발행 재시도 정책 (spec §2-4). 실패마다 attempts++.
 * attempts 1..5 는 지수 백오프로 재시도, attempts >= 6(5회 재시도 소진) 이면 DEAD.
 */
public final class RetryPolicy {

    /** attempts 가 이 값 이상이면 DEAD. */
    public static final int MAX_ATTEMPTS_BEFORE_DEAD = 6;

    /** 백오프(각 실패 후 다음 재시도 대기). index = attempts(1-based). */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1),   // attempts=1
            Duration.ofMinutes(5),   // attempts=2
            Duration.ofMinutes(15),  // attempts=3
            Duration.ofHours(1),     // attempts=4
            Duration.ofHours(6)      // attempts=5
    };

    private RetryPolicy() {
    }

    public static boolean isExhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS_BEFORE_DEAD;
    }

    /** attempts(1..5)에 해당하는 백오프. 범위를 벗어나면 최대치로 클램프. */
    public static Duration backoff(int attempts) {
        int idx = Math.max(1, Math.min(attempts, BACKOFF.length)) - 1;
        return BACKOFF[idx];
    }
}
