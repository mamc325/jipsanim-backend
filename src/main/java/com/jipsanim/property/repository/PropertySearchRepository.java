package com.jipsanim.property.repository;

import com.jipsanim.property.dto.PropertySearchCondition;
import com.jipsanim.property.dto.PropertySummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** QueryDSL 기반 매물 조건 검색 (ACTIVE 매물만). */
public interface PropertySearchRepository {

    Page<PropertySummaryResponse> search(PropertySearchCondition condition, Pageable pageable);
}
