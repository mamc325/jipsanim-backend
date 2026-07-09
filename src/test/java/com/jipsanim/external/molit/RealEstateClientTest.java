package com.jipsanim.external.molit;

import com.jipsanim.common.config.ExternalApiProperties;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.external.log.ExternalApiCallLogService;
import com.jipsanim.property.domain.DealType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RealEstateClientTest {

    private MockWebServer server;
    private RealEstateClient client;
    private ExternalApiCallLogService logService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        logService = mock(ExternalApiCallLogService.class);
        var endpoint = new ExternalApiProperties.Endpoint(server.url("/getRTMSDataSvcOffiRent").toString(), "key", 3000);
        client = new RealEstateClient(WebClient.builder(), new ExternalApiProperties(null, endpoint), logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("XML 을 파싱하고 만원→원 환산, 월세 0=전세/그 외=월세로 분류")
    void parseAndClassify() {
        server.enqueue(new MockResponse().setBody("""
                <?xml version="1.0" encoding="utf-8"?><response><header><resultCode>000</resultCode>
                <resultMsg>OK</resultMsg></header><body><items>
                <item><deposit>22,000</deposit><monthlyRent>0</monthlyRent><excluUseAr>29.87</excluUseAr>
                <dealYear>2026</dealYear><dealMonth>4</dealMonth><sggCd>11680</sggCd><umdNm>자곡동</umdNm></item>
                <item><deposit>15,700</deposit><monthlyRent>15</monthlyRent><excluUseAr>26.71</excluUseAr>
                <dealYear>2026</dealYear><dealMonth>4</dealMonth><sggCd>11680</sggCd><umdNm>자곡동</umdNm></item>
                </items><numOfRows>2</numOfRows><pageNo>1</pageNo><totalCount>2</totalCount></body></response>
                """));

        List<OfficetelRentTransaction> txns = client.fetch("11680", "202604");

        assertThat(txns).hasSize(2);
        assertThat(txns.get(0).deposit()).isEqualTo(220_000_000L);   // 22,000만원
        assertThat(txns.get(0).dealType()).isEqualTo(DealType.JEONSE);
        assertThat(txns.get(1).monthlyRent()).isEqualTo(150_000L);    // 15만원
        assertThat(txns.get(1).dealType()).isEqualTo(DealType.MONTHLY_RENT);
        assertThat(txns.get(1).sigunguCode()).isEqualTo("11680");
        verify(logService).saveSuccess(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("resultCode 가 000 이 아니면 502 로 실패하고 실패 로그를 남긴다")
    void apiError() {
        server.enqueue(new MockResponse().setBody("""
                <response><header><resultCode>22</resultCode><resultMsg>SERVICE ERROR</resultMsg></header>
                <body><items></items><totalCount>0</totalCount></body></response>
                """));

        assertThatThrownBy(() -> client.fetch("11680", "202604"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_REAL_ESTATE_API_ERROR);
        verify(logService).saveFailure(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt());
    }
}
