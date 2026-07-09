package com.jipsanim.loadtest;

import com.jipsanim.common.config.ExternalApiProperties;
import com.jipsanim.external.log.ExternalApiCallLogService;
import com.jipsanim.external.molit.RealEstateClient;
import com.jipsanim.pricestandard.batch.PriceStandardBatchService;
import com.jipsanim.pricestandard.candidate.PriceStandardCandidateGenerator;
import com.jipsanim.pricestandard.config.PriceStandardProperties;
import com.jipsanim.pricestandard.repository.PriceStandardBatchJobRepository;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 배치 bounded concurrency 병렬 수집 speedup 벤치마크 (WebClient).
 * MockWebServer 로 지연(150ms)을 주입해 concurrency=1 vs 8 wall-clock 을 비교한다.
 * → "비동기 병렬 수집" 주장의 실측 근거.
 */
@Tag("load")
class BatchConcurrencyBenchmark {

    private static final int REGIONS = 16;
    private static final long DELAY_MS = 150;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setBodyDelay(DELAY_MS, TimeUnit.MILLISECONDS)
                        .setBody("""
                                <response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>
                                <body><items><item><deposit>10,000</deposit><monthlyRent>50</monthlyRent>
                                <excluUseAr>30</excluUseAr><dealYear>2026</dealYear><dealMonth>6</dealMonth>
                                <sggCd>11680</sggCd><sggNm>강남구</sggNm></item></items>
                                <totalCount>1</totalCount></body></response>
                                """);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("배치 병렬 수집: concurrency 1 vs 8 (16개 지역, 지연 150ms)")
    void speedup() {
        long seq = runBatch(1);
        long par = runBatch(8);
        System.out.println("\n===== 배치 병렬 수집 speedup (16지역 x 150ms) =====");
        System.out.printf("concurrency=1: %d ms%n", seq);
        System.out.printf("concurrency=8: %d ms%n", par);
        System.out.printf("speedup: x%.1f%n", seq / (double) Math.max(1, par));
    }

    private long runBatch(int concurrency) {
        var endpoint = new ExternalApiProperties.Endpoint(server.url("/getRTMSDataSvcOffiRent").toString(), "k", 5000);
        var logService = mock(ExternalApiCallLogService.class);
        RealEstateClient client = new RealEstateClient(WebClient.builder(),
                new ExternalApiProperties(null, endpoint), logService);

        var batchJobRepo = mock(PriceStandardBatchJobRepository.class);
        when(batchJobRepo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        var generator = mock(PriceStandardCandidateGenerator.class);
        var props = new PriceStandardProperties(30, 1, concurrency, "IQR");

        PriceStandardBatchService service = new PriceStandardBatchService(client, batchJobRepo, generator, props);

        List<String> regions = new java.util.ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            regions.add("110" + (10 + i));
        }
        long start = System.nanoTime();
        service.run(1, regions, "BENCH");
        return (System.nanoTime() - start) / 1_000_000;
    }
}
