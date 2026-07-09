package com.jipsanim.external.address;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.config.ExternalApiProperties;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.external.address.dto.AddressSearchResponse;
import com.jipsanim.external.address.dto.AddressSearchResponse.AddressItem;
import com.jipsanim.external.log.ApiType;
import com.jipsanim.external.log.ExternalApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 행정안전부 도로명주소 검색 API 클라이언트(WebClient).
 * 응답을 표준 주소 후보로 변환하고, 호출 결과를 ExternalApiCallLog 에 남긴다.
 * 외부 장애는 502(EXTERNAL_ADDRESS_API_ERROR)로 반환하며 서버 오류로 전파하지 않는다.
 */
@Component
public class AddressClient {

    private static final Logger log = LoggerFactory.getLogger(AddressClient.class);
    private static final String SUCCESS_CODE = "0";

    private final WebClient webClient;
    private final ExternalApiProperties.Endpoint endpoint;
    private final ExternalApiCallLogService logService;
    private final ObjectMapper objectMapper;

    public AddressClient(WebClient.Builder builder, ExternalApiProperties properties,
                         ExternalApiCallLogService logService, ObjectMapper objectMapper) {
        this.endpoint = properties.address();
        this.webClient = builder.baseUrl(endpoint.baseUrl()).build();
        this.logService = logService;
        this.objectMapper = objectMapper;
    }

    public AddressSearchResponse search(String keyword, int currentPage, int countPerPage) {
        long start = System.currentTimeMillis();
        String params = "keyword=%s&currentPage=%d&countPerPage=%d&confmKey=***"
                .formatted(keyword, currentPage, countPerPage);
        try {
            // juso 는 JSON 을 text/html content-type 으로 반환할 수 있어 String 으로 받아 직접 파싱한다.
            String body = webClient.get()
                    .uri(uri -> uri
                            .queryParam("confmKey", endpoint.apiKey())
                            .queryParam("currentPage", currentPage)
                            .queryParam("countPerPage", countPerPage)
                            .queryParam("keyword", keyword)
                            .queryParam("resultType", "json")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(endpoint.timeoutMs()))
                    .block();

            int elapsed = (int) (System.currentTimeMillis() - start);
            JusoApiResponse response = objectMapper.readValue(body, JusoApiResponse.class);
            String errorCode = response == null || response.results() == null
                    || response.results().common() == null
                    ? null : response.results().common().errorCode();

            if (!SUCCESS_CODE.equals(errorCode)) {
                String message = errorMessage(response);
                logService.saveFailure(ApiType.ADDRESS, endpoint.baseUrl(), params, message, elapsed);
                throw new BusinessException(ErrorCode.EXTERNAL_ADDRESS_API_ERROR, message);
            }

            logService.saveSuccess(ApiType.ADDRESS, endpoint.baseUrl(), params, 200, elapsed);
            return toResponse(response);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            log.warn("address api call failed: {}", e.getMessage());
            logService.saveFailure(ApiType.ADDRESS, endpoint.baseUrl(), params, e.getMessage(), elapsed);
            throw new BusinessException(ErrorCode.EXTERNAL_ADDRESS_API_ERROR, "주소 조회 서비스 호출에 실패했습니다.");
        }
    }

    private String errorMessage(JusoApiResponse response) {
        if (response != null && response.results() != null && response.results().common() != null) {
            return response.results().common().errorMessage();
        }
        return "주소 API 응답이 비어 있습니다.";
    }

    private AddressSearchResponse toResponse(JusoApiResponse response) {
        JusoApiResponse.Results results = response.results();
        List<JusoApiResponse.Juso> jusoList = results.juso() == null ? List.of() : results.juso();
        List<AddressItem> items = jusoList.stream().map(this::toItem).collect(Collectors.toList());
        int total = parseInt(results.common().totalCount());
        return new AddressSearchResponse(items, total);
    }

    private AddressItem toItem(JusoApiResponse.Juso juso) {
        String bjdongCode = juso.admCd();
        String sigunguCode = bjdongCode != null && bjdongCode.length() >= 5 ? bjdongCode.substring(0, 5) : bjdongCode;
        String regionName = String.join(" ", nullToEmpty(juso.siNm()), nullToEmpty(juso.sggNm()),
                nullToEmpty(juso.emdNm())).trim().replaceAll(" +", " ");
        return new AddressItem(juso.roadAddr(), bjdongCode, sigunguCode, regionName, juso.zipNo());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
