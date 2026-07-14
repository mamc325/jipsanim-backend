package com.jipsanim.search;

import com.jipsanim.search.document.PropertyDocument;
import com.jipsanim.search.repository.PropertyDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5차 Phase 4(T533): nori 전문검색 관련도/복합어(decompound)/필터 조합/검증. ES 문서 직접 색인 후 /search 호출.
 */
@AutoConfigureMockMvc
class PropertySearchApiIntegrationTest extends ElasticsearchIntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PropertyDocumentRepository documentRepository;
    @Autowired
    ElasticsearchOperations operations;

    @BeforeEach
    void seed() {
        documentRepository.deleteAll();
        // A: 강남역 오피스텔(월세)
        documentRepository.save(PropertyDocument.of(1L)
                .title("강남역 5분 풀옵션 오피스텔").regionName("강남구").nearStation("강남역")
                .dealType("MONTHLY_RENT").propertyType("OFFICETEL").status("ACTIVE")
                .monthlyRent(700_000L).roomCount(1).realtorId(1L).createdAt(Instant.now().minusSeconds(300)));
        // B: 역세권 복합어 + 설명
        documentRepository.save(PropertyDocument.of(2L)
                .title("역삼 신축 오피스텔").description("역세권 풀옵션 한국전력공사 인근 즉시입주").regionName("강남구").nearStation("역삼역")
                .dealType("MONTHLY_RENT").propertyType("OFFICETEL").status("ACTIVE")
                .monthlyRent(900_000L).roomCount(1).realtorId(1L).createdAt(Instant.now().minusSeconds(200)));
        // C: 테헤란로 원룸(전세)
        documentRepository.save(PropertyDocument.of(3L)
                .title("테헤란로 원룸 전세").roadAddress("서울 강남구 테헤란로 5").regionName("강남구")
                .dealType("JEONSE").propertyType("OFFICETEL").status("ACTIVE")
                .roomCount(1).realtorId(1L).createdAt(Instant.now().minusSeconds(100)));
        // D: 비활성(DELETED) — 검색 제외돼야 함
        documentRepository.save(PropertyDocument.of(4L)
                .title("강남역 오피스텔 삭제됨").regionName("강남구").nearStation("강남역")
                .dealType("MONTHLY_RENT").propertyType("OFFICETEL").status("DELETED")
                .realtorId(1L).createdAt(Instant.now()));
        operations.indexOps(PropertyDocument.class).refresh(); // near-real-time → 즉시 검색 가능하게
    }

    @Test
    @DisplayName("관련도: '강남역 오피스텔' → 강남역 매물 상위, DELETED 제외")
    void relevance() throws Exception {
        mockMvc.perform(get("/api/properties/search").param("q", "강남역 오피스텔"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value(org.hamcrest.Matchers.containsString("강남역")))
                .andExpect(jsonPath("$.data.content[?(@.title=='강남역 오피스텔 삭제됨')]").doesNotExist());
    }

    @Test
    @DisplayName("복합어 decompound: 부분어 '전력' → 복합어 '한국전력공사' 포함 매물 노출")
    void decompound() throws Exception {
        // nori decompound_mode=mixed: '한국전력공사'가 [한국전력공사, 한국, 전력, 공사]로 색인되어 부분어 검색 매칭
        mockMvc.perform(get("/api/properties/search").param("q", "전력"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.propertyId==2)]").exists());
    }

    @Test
    @DisplayName("필터+q 조합: q=오피스텔 & dealType=MONTHLY_RENT → 전세(JEONSE) 제외")
    void filterCombo() throws Exception {
        mockMvc.perform(get("/api/properties/search")
                        .param("q", "오피스텔").param("dealType", "MONTHLY_RENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.dealType=='JEONSE')]").doesNotExist())
                .andExpect(jsonPath("$.data.content[?(@.propertyId==3)]").doesNotExist());
    }

    @Test
    @DisplayName("q 없음: ACTIVE 전체 최신순(총 3건, DELETED 제외)")
    void noQuery() throws Exception {
        mockMvc.perform(get("/api/properties/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("검증: size 초과(101) → 400")
    void invalidSize() throws Exception {
        mockMvc.perform(get("/api/properties/search").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검증: deep pagination (page+1)*size>10000 → 400")
    void deepPagination() throws Exception {
        mockMvc.perform(get("/api/properties/search").param("page", "200").param("size", "100"))
                .andExpect(status().isBadRequest());
    }
}
