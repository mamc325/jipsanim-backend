package com.jipsanim.property.view;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 조회수 writeback 주기 실행(6차). 테스트에서는 viewcount.writeback-enabled=false 로 비활성 —
 * 결정적 검증을 위해 {@link ViewCountWriteback#flush()} 를 직접 호출한다.
 */
@Component
@ConditionalOnProperty(name = "viewcount.writeback-enabled", havingValue = "true", matchIfMissing = true)
public class ViewCountWritebackScheduler {

    private static final Logger log = LoggerFactory.getLogger(ViewCountWritebackScheduler.class);

    private final ViewCountWriteback writeback;

    public ViewCountWritebackScheduler(ViewCountWriteback writeback) {
        this.writeback = writeback;
    }

    @Scheduled(fixedDelayString = "${viewcount.writeback-interval-ms:60000}")
    public void run() {
        try {
            writeback.flush();
        } catch (RuntimeException e) {
            log.warn("조회수 writeback 실패: {}", e.getMessage());
        }
    }
}
