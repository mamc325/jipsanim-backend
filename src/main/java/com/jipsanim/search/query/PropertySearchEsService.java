package com.jipsanim.search.query;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.dto.PropertySummaryResponse;
import com.jipsanim.search.document.PropertyDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * ES nori 전문검색 + 필터 + 관련도 정렬 (spec §2-4, contracts). status=ACTIVE 강제, track_total_hits,
 * tie-breaker propertyId desc. ES 장애 시 SEARCH_UNAVAILABLE(503).
 */
@Service
public class PropertySearchEsService {

    private static final Logger log = LoggerFactory.getLogger(PropertySearchEsService.class);
    private static final int MAX_RESULT_WINDOW = 10_000;

    private final ElasticsearchOperations operations;

    public PropertySearchEsService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    public Page<PropertySummaryResponse> search(PropertyEsSearchCondition c, Pageable pageable) {
        validate(c, pageable);
        NativeQuery query = buildQuery(c, pageable);
        try {
            SearchHits<PropertyDocument> hits = operations.search(query, PropertyDocument.class);
            List<PropertySummaryResponse> content = hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(PropertySearchEsService::toResponse)
                    .toList();
            return new PageImpl<>(content, pageable, hits.getTotalHits());
        } catch (Exception e) {
            log.warn("ES 검색 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
    }

    private NativeQuery buildQuery(PropertyEsSearchCondition c, Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (c.hasQuery()) {
            bool.must(m -> m.multiMatch(mm -> mm.query(c.q())
                    .fields("title^3", "nearStation^2", "regionName^2", "description")));
        } else {
            bool.must(m -> m.matchAll(a -> a));
        }

        // status=ACTIVE 항상 + 선택 필터(스코어 미반영)
        bool.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));
        term(bool, "sigunguCode", c.sigunguCode());
        term(bool, "dealType", c.dealType() != null ? c.dealType().name() : null);
        term(bool, "propertyType", c.propertyType() != null ? c.propertyType().name() : null);
        if (c.roomCount() != null) {
            bool.filter(f -> f.term(t -> t.field("roomCount").value(c.roomCount())));
        }
        numberRange(bool, "deposit", toD(c.minDeposit()), toD(c.maxDeposit()));
        numberRange(bool, "monthlyRent", toD(c.minRent()), toD(c.maxRent()));
        numberRange(bool, "area", toD(c.minArea()), toD(c.maxArea()));

        Query query = Query.of(q -> q.bool(bool.build()));
        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(query)
                .withPageable(pageable)
                .withTrackTotalHits(true);

        // 정렬 tie-breaker: q 있으면 _score→createdAt→propertyId, 없으면 createdAt→propertyId
        if (c.hasQuery()) {
            builder.withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
        }
        builder.withSort(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc)));
        builder.withSort(s -> s.field(f -> f.field("propertyId").order(SortOrder.Desc)));
        return builder.build();
    }

    private void term(BoolQuery.Builder bool, String field, String value) {
        if (value != null && !value.isBlank()) {
            bool.filter(f -> f.term(t -> t.field(field).value(value)));
        }
    }

    private void numberRange(BoolQuery.Builder bool, String field, Double min, Double max) {
        if (min == null && max == null) {
            return;
        }
        bool.filter(f -> f.range(r -> r.number(n -> {
            n.field(field);
            if (min != null) {
                n.gte(min);
            }
            if (max != null) {
                n.lte(max);
            }
            return n;
        })));
    }

    private static Double toD(Number n) {
        return n == null ? null : n.doubleValue();
    }

    private void validate(PropertyEsSearchCondition c, Pageable pageable) {
        checkRange(c.minDeposit(), c.maxDeposit(), "deposit");
        checkRange(c.minRent(), c.maxRent(), "monthlyRent");
        checkRange(c.minArea(), c.maxArea(), "area");
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page>=0, 1<=size<=100 이어야 합니다.");
        }
        // deep pagination 방지: from + size = (page+1)*size <= max_result_window
        long fromPlusSize = (long) (pageable.getPageNumber() + 1) * pageable.getPageSize();
        if (fromPlusSize > MAX_RESULT_WINDOW) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "(page+1)*size 는 " + MAX_RESULT_WINDOW + " 이하여야 합니다.");
        }
    }

    private void checkRange(Comparable<?> min, Comparable<?> max, String name) {
        if (min == null || max == null) {
            return;
        }
        if (min instanceof Long lo && max instanceof Long hi && lo > hi) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "min" + name + " <= max" + name + " 여야 합니다.");
        }
        if (min instanceof BigDecimal lo && max instanceof BigDecimal hi && lo.compareTo(hi) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "min" + name + " <= max" + name + " 여야 합니다.");
        }
    }

    private static PropertySummaryResponse toResponse(PropertyDocument d) {
        return new PropertySummaryResponse(
                d.getPropertyId(),
                d.getTitle(),
                d.getRegionName(),
                d.getDealType() != null ? com.jipsanim.property.domain.DealType.valueOf(d.getDealType()) : null,
                d.getDeposit(),
                d.getMonthlyRent(),
                d.getArea() != null ? BigDecimal.valueOf(d.getArea()) : null,
                d.getRoomCount(),
                d.getPrimaryImageUrl());
    }
}
