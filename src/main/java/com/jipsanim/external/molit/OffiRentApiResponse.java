package com.jipsanim.external.molit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

/**
 * 국토교통부 오피스텔 전월세 실거래가 API(getRTMSDataSvcOffiRent) XML 응답 매핑.
 */
@JacksonXmlRootElement(localName = "response")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OffiRentApiResponse(Header header, Body body) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String resultCode, String resultMsg) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Items items, Integer totalCount, Integer numOfRows, Integer pageNo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Items(
            @JacksonXmlElementWrapper(useWrapping = false)
            @JacksonXmlProperty(localName = "item")
            List<Item> item) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String sggCd,
            String sggNm,
            String umdNm,
            String offiNm,
            String deposit,
            String monthlyRent,
            String excluUseAr,
            Integer dealYear,
            Integer dealMonth) {
    }

    public boolean isOk() {
        return header != null && "000".equals(header.resultCode());
    }
}
