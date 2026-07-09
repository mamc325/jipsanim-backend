package com.jipsanim.pricestandard.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceStandardBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceStandardBatchScheduler.class);

    private final PriceStandardBatchService batchService;

    public PriceStandardBatchScheduler(PriceStandardBatchService batchService) {
        this.batchService = batchService;
    }

    /** 매월 1일 03:00 자동 수집 */
    @Scheduled(cron = "0 0 3 1 * *")
    public void collectMonthly() {
        log.info("scheduled price standard batch start");
        batchService.run(null, null, "SCHEDULER");
    }
}
