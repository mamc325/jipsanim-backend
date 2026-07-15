package com.jipsanim.property.repository;

import com.jipsanim.property.dto.PopularPropertyResponse;
import com.jipsanim.property.dto.PropertySearchCondition;
import com.jipsanim.property.dto.PropertySummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** QueryDSL 기반 매물 조건 검색 (ACTIVE 매물만). */
public interface PropertySearchRepository {

    Page<PropertySummaryResponse> search(PropertySearchCondition condition, Pageable pageable);

    /** 6차 인기목록: 주어진 id 중 ACTIVE 만 조회(순서 미보장 → 호출측이 ZSET 순서로 재정렬). */
    List<PopularPropertyResponse> findPopularByIds(List<Long> ids);

    /** 6차 인기목록 폴백(Redis 장애): ACTIVE 를 view_count desc 로 상위 N. */
    List<PopularPropertyResponse> findTopActiveByViewCount(int limit);
}
