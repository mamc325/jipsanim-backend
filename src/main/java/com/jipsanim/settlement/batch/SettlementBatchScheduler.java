package com.jipsanim.settlement.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매월 1일 04:00 전월 정산 배치 자동 실행. (cron 특성상 테스트 중엔 발화하지 않음)
 */
@Component
public class SettlementBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementBatchScheduler.class);

    private final SettlementBatchService batchService;

    public SettlementBatchScheduler(SettlementBatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(cron = "0 0 4 1 * *")
    public void runMonthly() {
        var result = batchService.run(null); // 전월
        log.info("scheduled settlement batch done: {}", result);
    }
}
