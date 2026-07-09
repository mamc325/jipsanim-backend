package com.jipsanim.external.molit;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.jipsanim.common.config.ExternalApiProperties;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.external.log.ApiType;
import com.jipsanim.external.log.ExternalApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 국토교통부 오피스텔 전월세 실거래가 API 클라이언트.
 * - data.go.kr WAF 회피를 위해 User-Agent 필수.
 * - XML 응답을 String 으로 받아 XmlMapper 로 파싱, 금액(만원)→원 정규화.
 * - 페이지네이션으로 해당 (시군구, 계약월) 전량 수집. 호출 결과는 ExternalApiCallLog 기록.
 */
@Component
public class RealEstateClient {

    private static final Logger log = LoggerFactory.getLogger(RealEstateClient.class);
    private static final String OK_CODE = "000";
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 20;

    private final WebClient webClient;
    private final ExternalApiProperties.Endpoint endpoint;
    private final ExternalApiCallLogService logService;
    private final XmlMapper xmlMapper = new XmlMapper();

    public RealEstateClient(WebClient.Builder builder, ExternalApiProperties properties,
                            ExternalApiCallLogService logService) {
        this.endpoint = properties.molit();
        this.webClient = builder
                .baseUrl(endpoint.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (jipsanim-backend)")
                .build();
        this.logService = logService;
    }

    /**
     * @param sigunguCode 법정동 앞 5자리(LAWD_CD)
     * @param dealYearMonth YYYYMM (DEAL_YMD)
     */
    public List<OfficetelRentTransaction> fetch(String sigunguCode, String dealYearMonth) {
        long start = System.currentTimeMillis();
        String params = "LAWD_CD=%s&DEAL_YMD=%s&serviceKey=***".formatted(sigunguCode, dealYearMonth);
        try {
            List<OffiRentApiResponse.Item> items = new ArrayList<>();
            int totalCount = Integer.MAX_VALUE;
            for (int page = 1; page <= MAX_PAGES && (page - 1) * PAGE_SIZE < totalCount; page++) {
                OffiRentApiResponse response = requestPage(sigunguCode, dealYearMonth, page);
                if (!response.isOk()) {
                    throw new BusinessException(ErrorCode.EXTERNAL_REAL_ESTATE_API_ERROR,
                            response.header() != null ? response.header().resultMsg() : "실거래가 응답 오류");
                }
                totalCount = response.body() != null && response.body().totalCount() != null
                        ? response.body().totalCount() : 0;
                if (response.body() != null && response.body().items() != null
                        && response.body().items().item() != null) {
                    items.addAll(response.body().items().item());
                }
            }
            int elapsed = (int) (System.currentTimeMillis() - start);
            logService.saveSuccess(ApiType.REAL_ESTATE_OFFICETEL_RENT, endpoint.baseUrl(), params, 200, elapsed);
            return items.stream().map(this::toTransaction).toList();
        } catch (BusinessException e) {
            logService.saveFailure(ApiType.REAL_ESTATE_OFFICETEL_RENT, endpoint.baseUrl(), params,
                    e.getMessage(), (int) (System.currentTimeMillis() - start));
            throw e;
        } catch (Exception e) {
            log.warn("real estate api call failed ({} {}): {}", sigunguCode, dealYearMonth, e.getMessage());
            logService.saveFailure(ApiType.REAL_ESTATE_OFFICETEL_RENT, endpoint.baseUrl(), params,
                    e.getMessage(), (int) (System.currentTimeMillis() - start));
            throw new BusinessException(ErrorCode.EXTERNAL_REAL_ESTATE_API_ERROR, "실거래가 조회 서비스 호출에 실패했습니다.");
        }
    }

    private OffiRentApiResponse requestPage(String sigunguCode, String dealYearMonth, int page) throws Exception {
        String body = webClient.get()
                .uri(uri -> uri
                        .queryParam("serviceKey", endpoint.apiKey())
                        .queryParam("LAWD_CD", sigunguCode)
                        .queryParam("DEAL_YMD", dealYearMonth)
                        .queryParam("pageNo", page)
                        .queryParam("numOfRows", PAGE_SIZE)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(endpoint.timeoutMs()))
                .block();
        return xmlMapper.readValue(body, OffiRentApiResponse.class);
    }

    private OfficetelRentTransaction toTransaction(OffiRentApiResponse.Item item) {
        long deposit = parseWon(item.deposit());
        long monthlyRent = parseWon(item.monthlyRent());
        DealType dealType = monthlyRent == 0 ? DealType.JEONSE : DealType.MONTHLY_RENT;
        return new OfficetelRentTransaction(item.sggCd(), item.sggNm(), dealType, deposit, monthlyRent,
                parseArea(item.excluUseAr()),
                item.dealYear() != null ? item.dealYear() : 0,
                item.dealMonth() != null ? item.dealMonth() : 0);
    }

    /** "22,000"(만원) → 220000000(원). 공백/빈값 → 0. */
    private long parseWon(String manwon) {
        if (manwon == null) {
            return 0;
        }
        String digits = manwon.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits) * 10_000L;
    }

    private BigDecimal parseArea(String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(area.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
