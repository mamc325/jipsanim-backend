package com.jipsanim.external.address.dto;

import java.util.List;

public record AddressSearchResponse(List<AddressItem> content, int totalCount) {

    public record AddressItem(
            String roadAddress,
            String bjdongCode,
            String sigunguCode,
            String regionName,
            String zipNo) {
    }
}
