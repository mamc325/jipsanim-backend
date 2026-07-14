package com.jipsanim.search.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.search.document.PropertyDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 기동 시 ES `property` 인덱스가 없으면 settings/mappings(nori) JSON 으로 생성한다(매핑 생성 책임 소유).
 * `@Document(createIndex=false)` 와 짝 — Spring Data 가 기본 매핑으로 먼저 만들지 못하게 한다(리뷰 P1).
 * `search.elasticsearch.enabled=true` 일 때만 빈 등록 → 일반 테스트는 ES 없이 기동.
 */
@Component
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
public class PropertyIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PropertyIndexBootstrap.class);
    private static final String MAPPING_RESOURCE = "es/property-index.json";

    private final ElasticsearchOperations operations;
    private final ObjectMapper objectMapper;

    public PropertyIndexBootstrap(ElasticsearchOperations operations, ObjectMapper objectMapper) {
        this.operations = operations;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        IndexOperations indexOps = operations.indexOps(PropertyDocument.class);
        if (indexOps.exists()) {
            log.info("ES property 인덱스 존재 — 생성 생략");
            return;
        }
        Map<String, Object> full = readMapping();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) full.get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) full.get("mappings");

        indexOps.create(settings, Document.from(mappings));
        log.info("ES property 인덱스 생성 완료 (nori korean_nori)");
    }

    private Map<String, Object> readMapping() throws Exception {
        try (var in = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        }
    }
}
