package com.jipsanim.property.popular;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.property.dto.PopularPropertyResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 인기 매물 목록(6차, 공개). 트렌딩 랭킹 Top-N(cache-aside). ACTIVE 제외는 DB 필터가 보장(P1).
 */
@RestController
public class PopularPropertyController {

    private final PopularPropertyService popularPropertyService;

    public PopularPropertyController(PopularPropertyService popularPropertyService) {
        this.popularPropertyService = popularPropertyService;
    }

    @GetMapping("/api/properties/popular")
    public ApiResponse<List<PopularPropertyResponse>> popular(
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(popularPropertyService.top(limit));
    }
}
