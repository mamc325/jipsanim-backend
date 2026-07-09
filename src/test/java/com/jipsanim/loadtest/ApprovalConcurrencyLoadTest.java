package com.jipsanim.loadtest;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.pricestandard.domain.CalcMethod;
import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 승인 정합성 부하 (Constitution II). 같은 후보를 N개 스레드가 동시에 승인 요청해도
 * 승인은 1건, 나머지는 멱등(409 ALREADY_REVIEWED), ACTIVE 기준은 1건만 존재해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Tag("load")
class ApprovalConcurrencyLoadTest {

    private static final int CONCURRENCY = 50;

    @LocalServerPort
    int port;
    @Autowired
    PriceStandardCandidateRepository candidateRepository;
    @Autowired
    PriceStandardRepository standardRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("동시 승인 50건 → 성공 1, 멱등 49, ACTIVE 1건")
    void concurrentApproval() throws Exception {
        String admin = adminToken();
        String sigungu = "19999";
        Long candidateId = candidateRepository.save(PriceStandardCandidate.create(
                sigungu, "테스트", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                5_000_000L, 50_000_000L, 550_000L, 1_800_000L, CalcMethod.IQR, 100,
                DataStatus.SUFFICIENT, "MOLIT_OFFICETEL_RENT", "2026-07", 1L)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> post("/api/admin/price-standard-candidates/" + candidateId + "/approval", admin));
        }
        List<Future<Integer>> results = pool.invokeAll(tasks);
        pool.shutdown();

        long ok = 0;
        long conflict = 0;
        long other = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                other++;
            }
        }
        long activeCount = standardRepository.search(PriceStandardStatus.ACTIVE, sigungu,
                org.springframework.data.domain.PageRequest.of(0, 100)).getTotalElements();

        System.out.println("\n===== 동시 승인 정합성 (요청 " + CONCURRENCY + ") =====");
        System.out.printf("성공(200)=%d, 멱등(409)=%d, 기타=%d, 최종 ACTIVE 기준=%d%n", ok, conflict, other, activeCount);

        // 핵심 불변식: 승인 1건, ACTIVE 기준 1건(중복 0). 나머지는 모두 409(멱등/경쟁 실패), 500 없음
        assertThat(ok).isEqualTo(1);
        assertThat(activeCount).isEqualTo(1);
        assertThat(conflict).isEqualTo(CONCURRENCY - 1);
        assertThat(other).isZero();
    }

    private int post(String path, String token) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private String adminToken() throws Exception {
        send("/api/auth/signup", null,
                "{\"email\":\"conc.admin@test.com\",\"password\":\"password1\",\"nickname\":\"a\",\"role\":\"ADMIN\"}");
        String body = send("/api/auth/login", null,
                "{\"email\":\"conc.admin@test.com\",\"password\":\"password1\"}");
        int i = body.indexOf("accessToken") + 14;
        return body.substring(i, body.indexOf('"', i));
    }

    private String send(String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
