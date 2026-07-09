package com.jipsanim.loadtest;

import com.jipsanim.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 매물 조건 검색 부하/벤치마크 (로컬 단일 노드 기준). ./gradlew loadTest
 * 10만 건 ACTIVE 매물 시드 후: 동시성별 처리량/지연, 깊은 페이지네이션 비용, 인덱스 효과 측정.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("load")
class SearchLoadTest {

    private static final int SEED_COUNT = 100_000;
    private static final String[] SIGUNGU = {
            "11680", "11650", "11440", "11710", "11170", "11200", "11215", "11230", "11260", "11290"
    };

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    void seed() {
        jdbc.update("insert into users(email,password,nickname,role,created_at,updated_at) "
                + "values('load@test.com','x','로드','REALTOR',now(),now())");
        Long userId = jdbc.queryForObject("select id from users where email='load@test.com'", Long.class);
        jdbc.update("insert into realtor(user_id,business_name,phone,created_at,updated_at) "
                + "values(?,'로드공인','010',now(),now())", userId);
        Long realtorId = jdbc.queryForObject("select id from realtor where user_id=?", Long.class, userId);

        String sql = "insert into property(realtor_id,title,description,road_address,bjdong_code,sigungu_code,"
                + "region_name,property_type,deal_type,deposit,monthly_rent,area,room_count,status,created_at,updated_at) "
                + "values(?,?,?,?,?,?,?, 'OFFICETEL','MONTHLY_RENT',?,?,?,?, 'ACTIVE', now(), now())";
        int chunk = 2000;
        for (int start = 0; start < SEED_COUNT; start += chunk) {
            int base = start;
            int size = Math.min(chunk, SEED_COUNT - start);
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    int n = base + i;
                    String sg = SIGUNGU[n % SIGUNGU.length];
                    ps.setLong(1, realtorId);
                    ps.setString(2, "매물 " + n);
                    ps.setString(3, "설명 텍스트 " + n + " 채광 좋은 오피스텔");
                    ps.setString(4, "서울 어딘가 " + n);
                    ps.setString(5, sg + "00000");
                    ps.setString(6, sg);
                    ps.setString(7, "지역 " + sg);
                    ps.setLong(8, 5_000_000 + (n % 45) * 1_000_000L);   // 보증금 5M~50M
                    ps.setLong(9, 300_000 + (n % 18) * 100_000L);       // 월세 30~200만
                    ps.setBigDecimal(10, java.math.BigDecimal.valueOf(20 + (n % 40)));
                    ps.setInt(11, 1 + (n % 3));
                }

                public int getBatchSize() {
                    return size;
                }
            });
        }
        Long count = jdbc.queryForObject("select count(*) from property where status='ACTIVE'", Long.class);
        System.out.println("[seed] ACTIVE properties = " + count);
    }

    @Test
    @DisplayName("검색 처리량/지연 (동시성 16/32/64)")
    void throughput() {
        warmup(300);
        System.out.println("\n===== 검색 부하 (GET /api/properties?sigunguCode=&size=20) =====");
        System.out.printf("%-8s %-10s %-10s %-10s %-10s %-8s%n", "동시성", "RPS", "p50(ms)", "p95(ms)", "p99(ms)", "에러");
        for (int c : new int[]{16, 32, 64}) {
            Stats s = runLoad(c, 4000);
            System.out.printf("%-8d %-10.0f %-10.1f %-10.1f %-10.1f %-8d%n",
                    c, s.rps, s.p50Ms(), s.p95Ms(), s.p99Ms(), s.errors);
        }
    }

    @Test
    @DisplayName("깊은 페이지네이션(offset) 비용: page=0 vs page=2000")
    void deepPagination() {
        long shallow = avgLatencyMs("/api/properties?size=20&page=0", 30);
        long deep = avgLatencyMs("/api/properties?size=20&page=2000", 30);
        System.out.println("\n===== 깊은 페이지네이션 =====");
        System.out.printf("page=0    평균 %d ms%n", shallow);
        System.out.printf("page=2000 평균 %d ms  (offset 40,000 스캔)%n", deep);
        System.out.printf("배율: x%.1f%n", deep / (double) Math.max(1, shallow));
    }

    @Test
    @DisplayName("인덱스 효과: idx_property_search 유무 비교")
    void indexEffect() {
        String url = "/api/properties?sigunguCode=11680&dealType=MONTHLY_RENT&size=20";
        long withIndex = avgLatencyMs(url, 30);
        jdbc.execute("drop index idx_property_search on property");
        long withoutIndex = avgLatencyMs(url, 30);
        jdbc.execute("create index idx_property_search on property(status, sigungu_code, deal_type, property_type)");
        System.out.println("\n===== 인덱스 효과 (필터 검색) =====");
        System.out.printf("인덱스 O: %d ms%n", withIndex);
        System.out.printf("인덱스 X: %d ms  (풀스캔)%n", withoutIndex);
        System.out.printf("개선 배율: x%.1f%n", withoutIndex / (double) Math.max(1, withIndex));
    }

    // ---- helpers ----

    private void warmup(int n) {
        for (int i = 0; i < n; i++) {
            get(url("/api/properties?size=20&sigunguCode=" + SIGUNGU[i % SIGUNGU.length]));
        }
    }

    private long avgLatencyMs(String path, int repeat) {
        get(url(path)); // warm
        long total = 0;
        for (int i = 0; i < repeat; i++) {
            long t0 = System.nanoTime();
            get(url(path));
            total += System.nanoTime() - t0;
        }
        return total / repeat / 1_000_000;
    }

    private Stats runLoad(int concurrency, long totalRequests) {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicLong counter = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long startWall = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < concurrency; w++) {
            futures.add(pool.submit(() -> {
                while (counter.incrementAndGet() <= totalRequests) {
                    String sg = SIGUNGU[ThreadLocalRandom.current().nextInt(SIGUNGU.length)];
                    long t0 = System.nanoTime();
                    int code = get(url("/api/properties?size=20&sigunguCode=" + sg));
                    latencies.add(System.nanoTime() - t0);
                    if (code != 200) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        pool.shutdown();
        double wallSec = (System.nanoTime() - startWall) / 1e9;
        long[] arr = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        return new Stats(totalRequests / wallSec, errors.get(), percentile(arr, 50), percentile(arr, 95),
                percentile(arr, 99));
    }

    private long percentile(long[] sortedNanos, int p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sortedNanos.length - 1L, Math.round(p / 100.0 * (sortedNanos.length - 1)));
        return sortedNanos[idx];
    }

    private int get(String url) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private record Stats(double rps, long errors, long p50Nanos, long p95Nanos, long p99Nanos) {
        double p50Ms() {
            return p50Nanos / 1_000_000.0;
        }

        double p95Ms() {
            return p95Nanos / 1_000_000.0;
        }

        double p99Ms() {
            return p99Nanos / 1_000_000.0;
        }
    }
}
