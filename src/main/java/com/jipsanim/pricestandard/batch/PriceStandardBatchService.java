package com.jipsanim.pricestandard.batch;

import com.jipsanim.external.molit.OfficetelRentTransaction;
import com.jipsanim.external.molit.RealEstateClient;
import com.jipsanim.pricestandard.config.PriceStandardProperties;
import com.jipsanim.pricestandard.domain.PriceStandardBatchJob;
import com.jipsanim.pricestandard.repository.PriceStandardBatchJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 실거래가 수집 배치. 대상 (시군구 × 계약월) 을 bounded concurrency 로 병렬 호출하고(Constitution V),
 * 지역별 성공/실패를 집계해 PriceStandardBatchJob 을 남긴다. (후보 생성은 Phase 4)
 */
@Service
public class PriceStandardBatchService {

    private static final Logger log = LoggerFactory.getLogger(PriceStandardBatchService.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    // 수집 대상 기본 시군구(미지정 시). MVP 서울 일부.
    private static final List<String> DEFAULT_SIGUNGU = List.of("11680", "11650", "11440", "11710");

    private final RealEstateClient realEstateClient;
    private final PriceStandardBatchJobRepository batchJobRepository;
    private final PriceStandardProperties properties;

    public PriceStandardBatchService(RealEstateClient realEstateClient,
                                     PriceStandardBatchJobRepository batchJobRepository,
                                     PriceStandardProperties properties) {
        this.realEstateClient = realEstateClient;
        this.batchJobRepository = batchJobRepository;
        this.properties = properties;
    }

    public BatchCollectResult run(Integer months, List<String> sigunguCodes, String triggeredBy) {
        int monthCount = months != null && months > 0 ? months : properties.collectMonths();
        List<String> regions = CollectionUtils.isEmpty(sigunguCodes) ? DEFAULT_SIGUNGU : sigunguCodes;
        List<String> yearMonths = recentYearMonths(monthCount);
        String jobMonth = YearMonth.now().toString();

        PriceStandardBatchJob job = batchJobRepository.save(PriceStandardBatchJob.start(jobMonth, triggeredBy));

        List<Target> targets = new ArrayList<>();
        for (String region : regions) {
            for (String ym : yearMonths) {
                targets.add(new Target(region, ym));
            }
        }

        List<Outcome> outcomes = collect(targets);
        List<OfficetelRentTransaction> transactions = outcomes.stream()
                .filter(Outcome::success)
                .flatMap(o -> o.transactions().stream())
                .toList();
        int success = (int) outcomes.stream().filter(Outcome::success).count();
        int fail = outcomes.size() - success;

        job.complete(targets.size(), success, fail);
        batchJobRepository.save(job);
        log.info("price standard batch done: job={} total={} success={} fail={} tx={}",
                job.getId(), targets.size(), success, fail, transactions.size());

        return new BatchCollectResult(job.getId(), job.getStatus(), targets.size(), success, fail, transactions);
    }

    private List<Outcome> collect(List<Target> targets) {
        return Flux.fromIterable(targets)
                .flatMap(target -> Mono
                                .fromCallable(() -> Outcome.ok(realEstateClient.fetch(target.sigungu(), target.yearMonth())))
                                .onErrorResume(e -> Mono.just(Outcome.failed()))
                                .subscribeOn(Schedulers.boundedElastic()),
                        properties.webclientConcurrency())
                .collectList()
                .block();
    }

    private List<String> recentYearMonths(int count) {
        List<String> result = new ArrayList<>();
        YearMonth cursor = YearMonth.now();
        for (int i = 0; i < count; i++) {
            result.add(cursor.format(YM));
            cursor = cursor.minusMonths(1);
        }
        return result;
    }

    private record Target(String sigungu, String yearMonth) {
    }

    private record Outcome(boolean success, List<OfficetelRentTransaction> transactions) {
        static Outcome ok(List<OfficetelRentTransaction> transactions) {
            return new Outcome(true, Objects.requireNonNullElseGet(transactions, List::of));
        }

        static Outcome failed() {
            return new Outcome(false, List.of());
        }
    }
}
