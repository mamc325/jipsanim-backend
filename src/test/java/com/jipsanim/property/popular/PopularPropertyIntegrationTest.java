package com.jipsanim.property.popular;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.dto.PopularPropertyResponse;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6차 Phase 2(T615·T616): 인기 랭킹 cache-aside + 일 감쇠. ACTIVE 제외는 DB 필터가 권위(P1).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PopularPropertyIntegrationTest {

    @Autowired
    PopularPropertyService popularPropertyService;
    @Autowired
    PopularRankingDecay decay;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void clear() {
        redis.delete(PropertyCacheKeys.PROPERTY_POPULAR);
        redis.delete(PropertyCacheKeys.POPULAR_LIST);
    }

    private Property newProperty(boolean active) {
        int n = SEQ.incrementAndGet();
        User u = userRepository.save(User.create("pop.realtor" + n + "@test.com", "pw", "r" + n, Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(u, "공인" + n, "010"));
        Property p = Property.createDraft(realtor, "강남역 오피스텔 " + n, "설명설명설명설명",
                "서울 강남구 테헤란로 " + n, "1168010100", "강남구", "강남역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, 10_000_000L, 700_000L, new BigDecimal("33"), 1);
        if (active) {
            p.approve();
        }
        return propertyRepository.save(p);
    }

    private void zadd(Long id, double score) {
        redis.opsForZSet().add(PropertyCacheKeys.PROPERTY_POPULAR, String.valueOf(id), score);
    }

    @Test
    @DisplayName("인기 Top-N: ZSET 트렌딩 순서(desc)로 반환, DB ACTIVE 필터")
    void topRanking() {
        Property a = newProperty(true);
        Property b = newProperty(true);
        Property c = newProperty(true);
        zadd(a.getId(), 5);
        zadd(b.getId(), 10);
        zadd(c.getId(), 1);

        List<PopularPropertyResponse> top = popularPropertyService.top(10);

        assertThat(top).extracting(PopularPropertyResponse::propertyId)
                .containsExactly(b.getId(), a.getId(), c.getId()); // score desc
    }

    @Test
    @DisplayName("ACTIVE 제외 권위: ZSET 에 stale(비ACTIVE/미존재) member 가 있어도 응답 미포함(P1)")
    void staleMemberExcludedByDbFilter() {
        Property active = newProperty(true);
        Property draft = newProperty(false);       // 비ACTIVE
        zadd(active.getId(), 5);
        zadd(draft.getId(), 100);                  // 높은 score 지만 ACTIVE 아님
        zadd(999_999L, 999);                        // 존재하지 않는 id

        List<PopularPropertyResponse> top = popularPropertyService.top(10);

        assertThat(top).extracting(PopularPropertyResponse::propertyId).containsExactly(active.getId());
    }

    @Test
    @DisplayName("cache-aside: 첫 조회 후 ZSET 을 바꿔도 TTL 내 캐시된 결과 유지(hit)")
    void cacheHit() {
        Property a = newProperty(true);
        zadd(a.getId(), 5);
        assertThat(popularPropertyService.top(10)).hasSize(1);

        // ZSET 비워도 캐시(popular:list)가 살아있어 동일 결과
        redis.delete(PropertyCacheKeys.PROPERTY_POPULAR);
        assertThat(popularPropertyService.top(10)).extracting(PopularPropertyResponse::propertyId)
                .containsExactly(a.getId());
    }

    @Test
    @DisplayName("일 감쇠: 전체 score × factor + 임계 이하 제거")
    void decayHalvesAndTrims() {
        Property a = newProperty(true);
        Property b = newProperty(true);
        zadd(a.getId(), 10);
        zadd(b.getId(), 1);   // 감쇠(×0.5)=0.5 ≤ epsilon(1.0) → 제거

        decay.decay();

        assertThat(redis.opsForZSet().score(PropertyCacheKeys.PROPERTY_POPULAR, String.valueOf(a.getId())))
                .isEqualTo(5.0);
        assertThat(redis.opsForZSet().score(PropertyCacheKeys.PROPERTY_POPULAR, String.valueOf(b.getId())))
                .isNull();
    }

    @Test
    @DisplayName("limit 검증: 0 또는 50 초과는 400(VALIDATION_ERROR)")
    void limitValidation() {
        assertThatThrownBy(() -> popularPropertyService.top(0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> popularPropertyService.top(51)).isInstanceOf(BusinessException.class);
    }
}
