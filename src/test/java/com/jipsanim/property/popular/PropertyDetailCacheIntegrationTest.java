package com.jipsanim.property.popular;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.view.ViewCountRedisConfig;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6차 Phase 2(T617·T618): 상세 cache-aside 역할별 읽기 + viewerKey dedup.
 * anonymous·USER=cache-first, REALTOR·ADMIN=캐시 우회→DB. 캐시 hit 여도 조회수 집계(P2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PropertyDetailCacheIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtTokenProvider tokenProvider;
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
        redis.delete(ViewCountRedisConfig.VIEW_PENDING);
        var dedup = redis.keys("view:dedup:*");
        if (dedup != null && !dedup.isEmpty()) {
            redis.delete(dedup);
        }
    }

    private Property activeProperty() {
        int n = SEQ.incrementAndGet();
        User u = userRepository.save(User.create("dc.realtor" + n + "@test.com", "pw", "r" + n, Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(u, "공인" + n, "010"));
        Property p = Property.createDraft(realtor, "강남역 오피스텔 " + n, "설명설명설명설명",
                "서울 강남구 테헤란로 " + n, "1168010100", "강남구", "강남역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, 10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve();
        Property saved = propertyRepository.save(p);
        redis.delete(PropertyCacheKeys.detailKey(saved.getId()));
        return saved;
    }

    private Long pendingDelta(Long id) {
        String v = redis.<String, String>opsForHash().get(ViewCountRedisConfig.VIEW_PENDING, String.valueOf(id));
        return v == null ? 0L : Long.parseLong(v);
    }

    @Test
    @DisplayName("anonymous cache-first: 첫 조회 miss→캐시 저장·집계, 재조회 hit·dedup(동일 IP 1회)")
    void anonymousCacheFirstAndDedup() throws Exception {
        Property p = activeProperty();

        mockMvc.perform(get("/api/properties/{id}", p.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").exists());

        assertThat(redis.hasKey(PropertyCacheKeys.detailKey(p.getId()))).isTrue(); // 캐시됨
        assertThat(pendingDelta(p.getId())).isEqualTo(1L);

        mockMvc.perform(get("/api/properties/{id}", p.getId())).andExpect(status().isOk()); // cache hit
        assertThat(pendingDelta(p.getId())).isEqualTo(1L); // 동일 viewerKey → dedup
    }

    @Test
    @DisplayName("USER: cache-first + viewerKey=u:{id}, 다른 사용자는 각각 집계")
    void userViewerKeyIndependent() throws Exception {
        Property p = activeProperty();
        User user1 = userRepository.save(User.create("dc.user1." + SEQ.incrementAndGet() + "@t.com", "pw", "u1", Role.USER));
        User user2 = userRepository.save(User.create("dc.user2." + SEQ.incrementAndGet() + "@t.com", "pw", "u2", Role.USER));
        String t1 = tokenProvider.createAccessToken(user1.getId(), Role.USER);
        String t2 = tokenProvider.createAccessToken(user2.getId(), Role.USER);

        mockMvc.perform(get("/api/properties/{id}", p.getId()).header("Authorization", "Bearer " + t1))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/properties/{id}", p.getId()).header("Authorization", "Bearer " + t1))
                .andExpect(status().isOk()); // 같은 유저 dedup
        assertThat(pendingDelta(p.getId())).isEqualTo(1L);

        mockMvc.perform(get("/api/properties/{id}", p.getId()).header("Authorization", "Bearer " + t2))
                .andExpect(status().isOk()); // 다른 유저 → +1
        assertThat(pendingDelta(p.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("REALTOR/ADMIN: 캐시 우회 → 캐시에 stale 이 있어도 DB 값 반환")
    void realtorAdminBypassCache() throws Exception {
        Property p = activeProperty();
        // 캐시에 stale 표현 주입(제목 위조)
        redis.opsForValue().set(PropertyCacheKeys.detailKey(p.getId()),
                "{\"propertyId\":" + p.getId() + ",\"title\":\"CACHED_STALE\",\"status\":\"ACTIVE\"}");

        // anonymous 는 cache-first → stale 제목
        mockMvc.perform(get("/api/properties/{id}", p.getId()))
                .andExpect(jsonPath("$.data.title").value("CACHED_STALE"));

        // REALTOR 는 우회 → DB 실제 제목
        User realtorUser = userRepository.save(User.create("dc.rlt." + SEQ.incrementAndGet() + "@t.com", "pw", "rl", Role.REALTOR));
        String rt = tokenProvider.createAccessToken(realtorUser.getId(), Role.REALTOR);
        mockMvc.perform(get("/api/properties/{id}", p.getId()).header("Authorization", "Bearer " + rt))
                .andExpect(jsonPath("$.data.title").value(p.getTitle()));

        // ADMIN 도 우회
        User adminUser = userRepository.save(User.create("dc.adm." + SEQ.incrementAndGet() + "@t.com", "pw", "ad", Role.ADMIN));
        String at = tokenProvider.createAccessToken(adminUser.getId(), Role.ADMIN);
        mockMvc.perform(get("/api/properties/{id}", p.getId()).header("Authorization", "Bearer " + at))
                .andExpect(jsonPath("$.data.title").value(p.getTitle()));
    }
}
