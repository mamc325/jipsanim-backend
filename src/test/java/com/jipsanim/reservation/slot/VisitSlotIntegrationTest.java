package com.jipsanim.reservation.slot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class VisitSlotIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    private final LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);

    private String realtorToken(String email) {
        User user = userRepository.save(User.create(email, "pw", "중개사", Role.REALTOR));
        realtorRepository.save(Realtor.create(user, "공인", "010"));
        return jwtTokenProvider.createAccessToken(user.getId(), Role.REALTOR);
    }

    private Long activeProperty(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        Realtor realtor = realtorRepository.findByUserId(user.getId()).orElseThrow();
        Property p = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "강남역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve(); // ACTIVE
        return propertyRepository.save(p).getId();
    }

    private String body(LocalDateTime s, LocalDateTime e) {
        return "{\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(s, e);
    }

    @Test
    @DisplayName("ACTIVE 매물에 슬롯 생성·목록·마감")
    void createListClose() throws Exception {
        String token = realtorToken("slot.realtor@test.com");
        long propertyId = activeProperty("slot.realtor@test.com");

        String res = mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(start, start.plusMinutes(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        long slotId = objectMapper.readTree(res).path("data").path("visitSlotId").asLong();

        // 공개 목록
        mockMvc.perform(get("/api/properties/{id}/visit-slots", propertyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].visitSlotId").value(slotId));

        // 마감
        mockMvc.perform(delete("/api/visit-slots/{id}", slotId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("생성 검증: 비ACTIVE·과거·역전·겹침은 409")
    void createValidation() throws Exception {
        String token = realtorToken("slot.valid@test.com");
        long propertyId = activeProperty("slot.valid@test.com");

        // 과거
        mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1).plusMinutes(30))))
                .andExpect(status().isConflict());

        // 시작>=종료
        mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(start, start)))
                .andExpect(status().isConflict());

        // 정상 하나 만든 뒤 겹치게
        mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body(start, start.plusMinutes(30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(start.plusMinutes(15), start.plusMinutes(45))))
                .andExpect(status().isConflict()); // 겹침
    }

    @Test
    @DisplayName("다른 중개사는 슬롯 생성 불가(403)")
    void notOwner() throws Exception {
        String owner = realtorToken("slot.owner@test.com");
        long propertyId = activeProperty("slot.owner@test.com");
        String other = realtorToken("slot.other@test.com");

        mockMvc.perform(post("/api/properties/{id}/visit-slots", propertyId)
                        .header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content(body(start, start.plusMinutes(30))))
                .andExpect(status().isForbidden());
    }
}
