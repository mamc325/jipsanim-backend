package com.jipsanim.search.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.property.dto.PropertySummaryResponse;
import com.jipsanim.search.query.PropertyEsSearchCondition;
import com.jipsanim.search.query.PropertySearchEsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 전문검색(nori). 공개 엔드포인트(SecurityConfig permitAll). ES 활성 시에만 빈 등록.
 * 기존 GET /api/properties(QueryDSL 조건검색)는 그대로 유지.
 */
@RestController
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
public class PropertySearchController {

    private final PropertySearchEsService searchService;

    public PropertySearchController(PropertySearchEsService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/properties/search")
    public ApiResponse<Page<PropertySummaryResponse>> search(
            PropertyEsSearchCondition condition,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(searchService.search(condition, pageable));
    }
}
