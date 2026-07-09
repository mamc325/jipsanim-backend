package com.jipsanim.property;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PropertySearchIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    PropertyRepository propertyRepository;

    private static final String SIGUNGU = "41135"; // 이 테스트 전용 (다른 테스트와 격리)
    private static final String BJDONG = "4113500000";

    @BeforeEach
    void seed() {
        User user = userRepository.save(User.create("search.realtor@test.com", "pw", "중개사", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(user, "분당공인", "010-0000-0000"));

        // A: ACTIVE 월세
        Property active1 = Property.createDraft(realtor, "분당 월세 오피스텔", "설명설명설명설명설명설명설명설명",
                "성남시 분당구 A로 1", BJDONG, "분당구", "정자역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33.0"), 1);
        active1.addImage("https://img/a.jpg", true, 0);
        active1.approve();
        propertyRepository.save(active1);

        // B: ACTIVE 전세
        Property active2 = Property.createDraft(realtor, "분당 전세 오피스텔", "설명설명설명설명설명설명설명설명",
                "성남시 분당구 B로 2", BJDONG, "분당구", "서현역", PropertyType.OFFICETEL, DealType.JEONSE,
                200_000_000L, null, new BigDecimal("45.0"), 2);
        active2.approve();
        propertyRepository.save(active2);

        // C: DRAFT (검색 제외 대상)
        Property draft = Property.createDraft(realtor, "분당 임시 매물", "설명설명설명설명설명설명설명설명",
                "성남시 분당구 C로 3", BJDONG, "분당구", "미금역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                5_000_000L, 500_000L, new BigDecimal("25.0"), 1);
        propertyRepository.save(draft);
    }

    @Test
    @DisplayName("ACTIVE 매물만 검색된다 (DRAFT 제외)")
    void onlyActive() throws Exception {
        mockMvc.perform(get("/api/properties").param("sigunguCode", SIGUNGU))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("거래유형 필터")
    void dealTypeFilter() throws Exception {
        mockMvc.perform(get("/api/properties").param("sigunguCode", SIGUNGU).param("dealType", "MONTHLY_RENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].dealType").value("MONTHLY_RENT"))
                .andExpect(jsonPath("$.data.content[0].primaryImageUrl").value("https://img/a.jpg"));
    }

    @Test
    @DisplayName("보증금 범위 필터")
    void depositRangeFilter() throws Exception {
        mockMvc.perform(get("/api/properties").param("sigunguCode", SIGUNGU).param("maxDeposit", "50000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)); // 월세(1천만)만, 전세(2억) 제외
    }
}
