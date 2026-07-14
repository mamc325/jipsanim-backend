package com.jipsanim.search.index;

import com.jipsanim.outbox.publisher.OutboxEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * ES 색인 이벤트 적재. 도메인 서비스가 ACTIVE 진입/이탈 시 호출한다.
 * `search.elasticsearch.enabled=false` 면 no-op → 색인 disabled 시 고아 이벤트/DEAD 누적 없음(리뷰 P1).
 * event_key 에 UUID generation 부착 → 반복 전이에서도 각 회차가 별도 이벤트(리뷰 P1).
 */
@Component
public class PropertyIndexEventRecorder {

    private final OutboxEventPublisher publisher;
    private final boolean enabled;

    public PropertyIndexEventRecorder(OutboxEventPublisher publisher,
                                      @Value("${search.elasticsearch.enabled:false}") boolean enabled) {
        this.publisher = publisher;
        this.enabled = enabled;
    }

    /** ACTIVE 진입(승인): PROPERTY_INDEX 적재. */
    public void recordIndex(Long propertyId) {
        if (!enabled) {
            return;
        }
        String key = "PROPERTY_INDEX:%d:%s".formatted(propertyId, UUID.randomUUID());
        publisher.append("PROPERTY", propertyId, "PROPERTY_INDEX", key, Map.of("propertyId", propertyId));
    }

    /** ACTIVE 이탈(삭제/반려 등): PROPERTY_UNINDEX 적재. */
    public void recordUnindex(Long propertyId) {
        if (!enabled) {
            return;
        }
        String key = "PROPERTY_UNINDEX:%d:%s".formatted(propertyId, UUID.randomUUID());
        publisher.append("PROPERTY", propertyId, "PROPERTY_UNINDEX", key, Map.of("propertyId", propertyId));
    }
}
