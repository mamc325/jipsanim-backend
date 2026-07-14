package com.jipsanim.search;

import com.jipsanim.search.document.PropertyDocument;
import com.jipsanim.search.repository.PropertyDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5차 Phase 1(T504): nori 이미지 기동 + Bootstrap 이 nori 매핑으로 인덱스 생성 + 문서 round-trip 검증.
 */
class PropertyIndexBootstrapIntegrationTest extends ElasticsearchIntegrationTestSupport {

    @Autowired
    ElasticsearchOperations operations;
    @Autowired
    PropertyDocumentRepository repository;

    @Test
    @DisplayName("ES 기동 + property 인덱스 생성(nori korean_nori analyzer)")
    void indexCreatedWithNori() {
        assertThat(ES.isRunning()).isTrue();

        var indexOps = operations.indexOps(PropertyDocument.class);
        assertThat(indexOps.exists()).isTrue();

        // settings 에 커스텀 nori analyzer 가 적용됐는지
        assertThat(indexOps.getSettings().toString()).contains("korean_nori");
    }

    @Test
    @DisplayName("문서 저장/조회 round-trip")
    void saveAndFind() {
        PropertyDocument doc = PropertyDocument.of(1001L)
                .title("강남역 5분 풀옵션 오피스텔")
                .regionName("강남구")
                .nearStation("강남역")
                .dealType("MONTHLY_RENT")
                .propertyType("OFFICETEL")
                .status("ACTIVE")
                .deposit(10_000_000L)
                .monthlyRent(700_000L)
                .area(33.0)
                .roomCount(1)
                .realtorId(3L)
                .primaryImageUrl("https://cdn.example.com/1.jpg")
                .createdAt(Instant.now());
        repository.save(doc);

        var found = repository.findById("1001");
        assertThat(found).isPresent();
        assertThat(found.get().getPropertyId()).isEqualTo(1001L);
        assertThat(found.get().getTitle()).contains("오피스텔");
    }
}
