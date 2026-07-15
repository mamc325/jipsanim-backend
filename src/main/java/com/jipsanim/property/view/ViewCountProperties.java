package com.jipsanim.property.view;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 조회수 카운팅 설정(6차). window=중복조회 dedup 윈도우, trustProxy=신뢰 프록시 뒤에서만 XFF 사용.
 * writeback-enabled / writeback-interval-ms 는 스케줄러 애노테이션이 직접 읽는다.
 */
@ConfigurationProperties(prefix = "viewcount")
public record ViewCountProperties(long windowSeconds, boolean trustProxy) {

    public ViewCountProperties {
        if (windowSeconds <= 0) {
            windowSeconds = 1800L; // 기본 30분
        }
    }
}
