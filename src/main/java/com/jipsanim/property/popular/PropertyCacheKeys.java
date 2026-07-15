package com.jipsanim.property.popular;

/** 6차 인기/상세 캐시 Redis 키. 랭킹 zset 은 {@code property:popular}(조회수 카운트 Lua 와 공유). */
public final class PropertyCacheKeys {

    /** 트렌딩 랭킹 zset(조회수 ZINCRBY / 일 감쇠). */
    public static final String PROPERTY_POPULAR = "property:popular";
    /** 인기목록 캐시(단일 키, Top-max JSON). */
    public static final String POPULAR_LIST = "popular:list";

    private PropertyCacheKeys() {
    }

    /** ACTIVE 공개 상세 캐시(JSON). */
    public static String detailKey(Long propertyId) {
        return "property:detail:" + propertyId;
    }
}
