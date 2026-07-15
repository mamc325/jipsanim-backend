package com.jipsanim.property.popular;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.dto.PopularPropertyResponse;
import com.jipsanim.property.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인기 매물 목록(6차). cache-aside 단일 키({@code popular:list}, Top-max).
 * miss 시 over-fetch → DB ACTIVE 필터 → ZSET 순서 복원 → 상위 max 캐시.
 * Redis 장애 시 DB {@code view_count} desc 폴백(원칙 V, degrade). ACTIVE 제외 보장은 DB 필터(권위, P1).
 */
@Service
public class PopularPropertyService {

    private static final Logger log = LoggerFactory.getLogger(PopularPropertyService.class);
    private static final TypeReference<List<PopularPropertyResponse>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final PropertyRepository propertyRepository;
    private final ObjectMapper objectMapper;
    private final PopularProperties props;

    public PopularPropertyService(StringRedisTemplate redis, PropertyRepository propertyRepository,
                                  ObjectMapper objectMapper, PopularProperties props) {
        this.redis = redis;
        this.propertyRepository = propertyRepository;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public List<PopularPropertyResponse> top(int limit) {
        if (limit < 1 || limit > props.max()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit 은 1~" + props.max() + " 여야 합니다.");
        }

        String cached = tryGet();
        if (cached != null) {
            return slice(deserialize(cached), limit); // cache hit
        }
        List<Long> ids = tryReverseRange();
        if (ids == null) {
            return propertyRepository.findTopActiveByViewCount(limit); // Redis 장애 → DB 폴백
        }
        List<PopularPropertyResponse> ordered = assemble(ids);
        trySet(serialize(ordered));
        return slice(ordered, limit);
    }

    /** ZSET id 순서(트렌딩 desc)를 DB 결과에 복원 + ACTIVE 필터 + 상위 max (P4·P5). */
    private List<PopularPropertyResponse> assemble(List<Long> ids) {
        Map<Long, PopularPropertyResponse> byId = new HashMap<>();
        for (PopularPropertyResponse r : propertyRepository.findPopularByIds(ids)) {
            byId.put(r.propertyId(), r); // DB ACTIVE 필터 결과만 존재(stale/inactive 제외)
        }
        List<PopularPropertyResponse> ordered = new ArrayList<>();
        for (Long id : ids) {
            PopularPropertyResponse r = byId.get(id);
            if (r != null) {
                ordered.add(r);
                if (ordered.size() >= props.max()) {
                    break;
                }
            }
        }
        return ordered;
    }

    private List<PopularPropertyResponse> slice(List<PopularPropertyResponse> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }

    private String tryGet() {
        try {
            return redis.opsForValue().get(PropertyCacheKeys.POPULAR_LIST);
        } catch (RuntimeException e) {
            log.warn("popular:list 캐시 조회 실패(degrade): {}", e.getMessage());
            return null;
        }
    }

    private List<Long> tryReverseRange() {
        try {
            Set<String> ids = redis.opsForZSet()
                    .reverseRange(PropertyCacheKeys.PROPERTY_POPULAR, 0, props.overfetch() - 1L);
            List<Long> result = new ArrayList<>();
            if (ids != null) {
                for (String s : ids) {
                    result.add(Long.parseLong(s));
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("property:popular ZREVRANGE 실패(degrade): {}", e.getMessage());
            return null;
        }
    }

    private void trySet(String json) {
        try {
            redis.opsForValue().set(PropertyCacheKeys.POPULAR_LIST, json,
                    Duration.ofSeconds(props.listTtlSeconds()));
        } catch (RuntimeException e) {
            log.warn("popular:list 캐시 저장 실패(무시): {}", e.getMessage());
        }
    }

    private String serialize(List<PopularPropertyResponse> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("인기목록 직렬화 실패", e);
        }
    }

    private List<PopularPropertyResponse> deserialize(String json) {
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("인기목록 역직렬화 실패", e);
        }
    }
}
