package com.jipsanim.external.address;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.config.ExternalApiProperties;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.external.address.dto.AddressSearchResponse;
import com.jipsanim.external.log.ExternalApiCallLogService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AddressClientTest {

    private MockWebServer server;
    private AddressClient addressClient;
    private ExternalApiCallLogService logService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        logService = mock(ExternalApiCallLogService.class);
        var endpoint = new ExternalApiProperties.Endpoint(server.url("/addrLinkApi.do").toString(), "test-key", 3000);
        var properties = new ExternalApiProperties(endpoint, null);
        addressClient = new AddressClient(WebClient.builder(), properties, logService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("정상 응답을 표준 주소 후보로 변환하고 성공 로그를 남긴다")
    void searchSuccess() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html;charset=UTF-8") // juso 는 text/html 로 JSON 반환
                .setBody("""
                        {"results":{"common":{"errorCode":"0","errorMessage":"정상","totalCount":"12",
                        "currentPage":"1","countPerPage":"10"},
                        "juso":[{"roadAddr":"서울특별시 강남구 테헤란로 101 (역삼동)","admCd":"1168010100",
                        "siNm":"서울특별시","sggNm":"강남구","emdNm":"역삼동","zipNo":"06134","jibunAddr":"..."}]}}
                        """));

        AddressSearchResponse result = addressClient.search("강남구 테헤란로", 1, 10);

        assertThat(result.totalCount()).isEqualTo(12);
        assertThat(result.content()).hasSize(1);
        AddressSearchResponse.AddressItem item = result.content().get(0);
        assertThat(item.bjdongCode()).isEqualTo("1168010100");
        assertThat(item.sigunguCode()).isEqualTo("11680");
        assertThat(item.regionName()).isEqualTo("서울특별시 강남구 역삼동");
        verify(logService).saveSuccess(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("errorCode 가 0이 아니면 502(EXTERNAL_ADDRESS_API_ERROR)로 실패하고 실패 로그를 남긴다")
    void searchApiError() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":{"common":{"errorCode":"E0001","errorMessage":"인증키가 유효하지 않습니다.",
                        "totalCount":"0","currentPage":"1","countPerPage":"10"},"juso":[]}}
                        """));

        assertThatThrownBy(() -> addressClient.search("강남구", 1, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_ADDRESS_API_ERROR);

        verify(logService).saveFailure(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
