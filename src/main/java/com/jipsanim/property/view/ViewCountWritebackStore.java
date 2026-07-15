package com.jipsanim.property.view;

import com.jipsanim.property.repository.PropertyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * writeback 배출분을 <b>단일 트랜잭션</b>으로 DB `view_count` 에 증분 반영(6차 P1).
 * (스케줄러와 분리 — @Transactional 자기호출 프록시 우회 방지)
 */
@Component
public class ViewCountWritebackStore {

    private final PropertyRepository propertyRepository;

    public ViewCountWritebackStore(PropertyRepository propertyRepository) {
        this.propertyRepository = propertyRepository;
    }

    @Transactional
    public void applyDeltas(Map<Object, Object> deltas) {
        for (Map.Entry<Object, Object> e : deltas.entrySet()) {
            long id = Long.parseLong(String.valueOf(e.getKey()));
            long delta = Long.parseLong(String.valueOf(e.getValue()));
            if (delta != 0) {
                propertyRepository.addViewCount(id, delta);
            }
        }
    }
}
