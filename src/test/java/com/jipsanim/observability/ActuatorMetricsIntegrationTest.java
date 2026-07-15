package com.jipsanim.observability;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.property.popular.PopularPropertyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6차 Phase 3(T624): Authorization 없이 /actuator/prometheus·/actuator/health → 200(permitAll).
 * 커스텀 메트릭 노출 확인. (actuator 는 앱 포트와 동일, 외부 노출은 프록시/네트워크에서 차단)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ActuatorMetricsIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    PopularPropertyService popularPropertyService;

    @Test
    @DisplayName("무인증 GET /actuator/prometheus → 200 + 커스텀 메트릭 노출")
    void prometheusUnauthenticated() {
        popularPropertyService.top(5); // cache miss 메트릭 유발 → cache_requests_total 등록

        ResponseEntity<String> res = rest.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class); // Authorization 없음

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("cache_requests_total");
    }

    @Test
    @DisplayName("무인증 GET /actuator/health → 200")
    void healthUnauthenticated() {
        ResponseEntity<String> res = rest.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
