package com.jipsanim.outbox.repository;

import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 폴링 선점: PENDING & next_retry_at <= now 를 id 순으로 배치 조회.
     * FOR UPDATE SKIP LOCKED 로 다중 워커에서도 한 이벤트를 한 워커만 선점.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' AND next_retry_at <= :now "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * reaper: 처리 중 crash 로 PROCESSING 에 고착된 이벤트를 PENDING 으로 복구.
     * @return 복구된 건수
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE outbox_event SET status = 'PENDING' "
            + "WHERE status = 'PROCESSING' AND processing_started_at < :cutoff", nativeQuery = true)
    int reclaimStuck(@Param("cutoff") LocalDateTime cutoff);

    Page<OutboxEvent> findByStatus(OutboxStatus status, Pageable pageable);

    long countByEventKey(String eventKey);

    long countByEventTypeAndAggregateId(String eventType, Long aggregateId);

    /**
     * Producer 멱등 적재: event_key 중복이면 `ON DUPLICATE KEY UPDATE id=id` 로 no-op.
     * 예외를 던지지 않아 도메인 트랜잭션이 rollback-only 로 오염되지 않는다(리뷰 P1).
     * created_at/updated_at 은 native insert 라 직접 채운다(auditing 우회).
     */
    @Modifying
    @Query(value = "INSERT INTO outbox_event "
            + "(aggregate_type, aggregate_id, event_type, event_key, payload, status, attempts, next_retry_at, created_at, updated_at) "
            + "VALUES (:aggregateType, :aggregateId, :eventType, :eventKey, :payload, 'PENDING', 0, :now, :now, :now) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    void appendIgnoringDuplicate(@Param("aggregateType") String aggregateType,
                                 @Param("aggregateId") Long aggregateId,
                                 @Param("eventType") String eventType,
                                 @Param("eventKey") String eventKey,
                                 @Param("payload") String payload,
                                 @Param("now") LocalDateTime now);
}
