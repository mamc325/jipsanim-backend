package com.jipsanim.external.address;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 행정안전부 도로명주소 검색 API(addrLinkApi.do) 응답 매핑.
 * 필요한 필드만 정의하고 나머지는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JusoApiResponse(Results results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Results(Common common, List<Juso> juso) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Common(String errorCode, String errorMessage, String totalCount,
                         String currentPage, String countPerPage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Juso(String roadAddr, String admCd, String siNm, String sggNm,
                      String emdNm, String zipNo, String jibunAddr) {
    }
}
