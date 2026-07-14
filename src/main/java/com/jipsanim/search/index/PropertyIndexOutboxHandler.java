package com.jipsanim.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.worker.OutboxEventHandler;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyImage;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.search.document.PropertyDocument;
import com.jipsanim.search.repository.PropertyDocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 색인 이벤트(PROPERTY_INDEX/UNINDEX) 처리기. Worker 라우팅으로 알림과 분리된다(Phase 2).
 * INDEX → 최신 매물 재조회 후 ACTIVE 면 upsert, 아니면 방어적 삭제. UNINDEX → 문서 삭제.
 * `search.elasticsearch.enabled=true` 일 때만 빈 등록.
 */
@Component
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
public class PropertyIndexOutboxHandler implements OutboxEventHandler {

    private final PropertyRepository propertyRepository;
    private final PropertyDocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public PropertyIndexOutboxHandler(PropertyRepository propertyRepository,
                                      PropertyDocumentRepository documentRepository,
                                      ObjectMapper objectMapper) {
        this.propertyRepository = propertyRepository;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return "PROPERTY_INDEX".equals(eventType) || "PROPERTY_UNINDEX".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        long propertyId = parsePropertyId(event.getPayload());
        if ("PROPERTY_UNINDEX".equals(event.getEventType())) {
            documentRepository.deleteById(String.valueOf(propertyId));
            return;
        }
        // PROPERTY_INDEX: 최신 매물 재조회 후 upsert(ACTIVE), 아니면 방어적 삭제
        Property property = propertyRepository.findById(propertyId).orElse(null);
        if (property == null || property.getStatus() != PropertyStatus.ACTIVE) {
            documentRepository.deleteById(String.valueOf(propertyId));
            return;
        }
        documentRepository.save(toDocument(property));
    }

    private PropertyDocument toDocument(Property p) {
        return PropertyDocument.of(p.getId())
                .title(p.getTitle())
                .description(p.getDescription())
                .roadAddress(p.getRoadAddress())
                .regionName(p.getRegionName())
                .nearStation(p.getNearStation())
                .sigunguCode(p.getSigunguCode())
                .dealType(p.getDealType() != null ? p.getDealType().name() : null)
                .propertyType(p.getPropertyType() != null ? p.getPropertyType().name() : null)
                .status(p.getStatus().name())
                .primaryImageUrl(primaryImageUrl(p))
                .deposit(p.getDeposit())
                .monthlyRent(p.getMonthlyRent())
                .area(p.getArea() != null ? p.getArea().doubleValue() : null)
                .roomCount(p.getRoomCount())
                .realtorId(p.getRealtor().getId())
                .createdAt(p.getCreatedAt() != null
                        ? p.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant() : null);
    }

    private String primaryImageUrl(Property p) {
        return p.getImages().stream()
                .filter(PropertyImage::isPrimary)
                .map(PropertyImage::getImageUrl)
                .findFirst()
                .orElseGet(() -> p.getImages().stream()
                        .map(PropertyImage::getImageUrl)
                        .findFirst()
                        .orElse(null));
    }

    private long parsePropertyId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "{}" : payload);
            return node.path("propertyId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("PROPERTY_INDEX payload 파싱 실패", e);
        }
    }
}
