package com.jipsanim.property.view;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.dto.PropertyDetailResult;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.service.PropertyService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6차 Phase 1(T604·T605): 조회수 카운팅 — 원자 Lua dedup, writeback 원자 배출(유실 0),
 * getDetail 공개표현 게이트.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ViewCountIntegrationTest {

    @Autowired
    ViewCountService viewCountService;
    @Autowired
    ViewCountWriteback writeback;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    PropertyService propertyService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void clearRedis() {
        redis.delete(ViewCountRedisConfig.VIEW_PENDING);
        redis.delete(ViewCountRedisConfig.VIEW_FLUSHING);
        redis.delete(ViewCountRedisConfig.PROPERTY_POPULAR);
        var dedup = redis.keys("view:dedup:*");
        if (dedup != null && !dedup.isEmpty()) {
            redis.delete(dedup);
        }
    }

    private Property newProperty(boolean active) {
        int n = SEQ.incrementAndGet();
        User u = userRepository.save(User.create("vc.realtor" + n + "@test.com", "pw", "r" + n, Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(u, "공인" + n, "010"));
        Property p = Property.createDraft(realtor, "강남역 오피스텔", "설명설명설명설명",
                "서울 강남구 테헤란로 " + n, "1168010100", "강남구", "강남역",
                PropertyType.OFFICETEL, DealType.MONTHLY_RENT, 10_000_000L, 700_000L, new BigDecimal("33"), 1);
        if (active) {
            p.approve();
        }
        return propertyRepository.save(p);
    }

    @Test
    @DisplayName("dedup: 같은 viewerKey 는 윈도우 내 1회만 집계 → writeback 후 view_count +1")
    void dedupThenWriteback() {
        Property p = newProperty(true);

        assertThat(viewCountService.record(p.getId(), "ip:1.1.1.1")).isTrue();  // 최초
        assertThat(viewCountService.record(p.getId(), "ip:1.1.1.1")).isFalse(); // 중복 skip

        assertThat(redis.<String, String>opsForHash().get(ViewCountRedisConfig.VIEW_PENDING, String.valueOf(p.getId())))
                .isEqualTo("1");

        writeback.flush();

        assertThat(propertyRepository.findById(p.getId()).orElseThrow().getViewCount()).isEqualTo(1L);
        assertThat(redis.hasKey(ViewCountRedisConfig.VIEW_PENDING)).isFalse();
    }

    @Test
    @DisplayName("다른 viewerKey 는 각각 집계 + 랭킹 ZINCRBY")
    void differentViewers() {
        Property p = newProperty(true);

        viewCountService.record(p.getId(), "ip:1.1.1.1");
        viewCountService.record(p.getId(), "u:42");

        writeback.flush();

        assertThat(propertyRepository.findById(p.getId()).orElseThrow().getViewCount()).isEqualTo(2L);
        assertThat(redis.opsForZSet().score(ViewCountRedisConfig.PROPERTY_POPULAR, String.valueOf(p.getId())))
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("writeback 유실 0: 배출(flushing) 중 유입 증가분은 새 pending 으로 보존")
    void writebackLossless() {
        Property p = newProperty(true);

        viewCountService.record(p.getId(), "ip:1.1.1.1");                 // pending{p:1}
        redis.rename(ViewCountRedisConfig.VIEW_PENDING, ViewCountRedisConfig.VIEW_FLUSHING); // 배출 스냅샷 떼어냄
        viewCountService.record(p.getId(), "ip:2.2.2.2");                 // 배출 중 유입 → 새 pending{p:1}

        writeback.flush(); // flushing(스냅샷) 먼저 반영 → view_count +1, flushing 삭제. pending 유지
        assertThat(propertyRepository.findById(p.getId()).orElseThrow().getViewCount()).isEqualTo(1L);

        writeback.flush(); // 남은 pending 반영 → +1
        assertThat(propertyRepository.findById(p.getId()).orElseThrow().getViewCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getDetail 게이트: ACTIVE=공개표현(countable=true), 소유자의 DRAFT=false")
    void countablePublicAccessGate() {
        Property active = newProperty(true);
        PropertyDetailResult r1 = propertyService.getDetail(null, active.getId());
        assertThat(r1.countablePublicAccess()).isTrue();

        Property draft = newProperty(false);
        Long ownerUserId = draft.getRealtor().getUser().getId();
        PropertyDetailResult r2 = propertyService.getDetail(new AuthUser(ownerUserId, Role.REALTOR), draft.getId());
        assertThat(r2.countablePublicAccess()).isFalse(); // 비공개 표현 → 미집계
    }

    @Test
    @DisplayName("빈 pending 이면 writeback 은 no-op")
    void writebackNoop() {
        writeback.flush(); // 예외 없이 통과
        assertThat(redis.hasKey(ViewCountRedisConfig.VIEW_FLUSHING)).isFalse();
    }
}
