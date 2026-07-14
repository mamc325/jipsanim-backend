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
}
