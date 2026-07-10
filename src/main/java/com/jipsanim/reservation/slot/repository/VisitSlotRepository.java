package com.jipsanim.reservation.slot.repository;

import com.jipsanim.reservation.slot.domain.VisitSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VisitSlotRepository extends JpaRepository<VisitSlot, Long> {

    List<VisitSlot> findByPropertyIdOrderByStartTimeAsc(Long propertyId);

    /** 시간 겹침(점유 상태 OPEN/RESERVED)과 교차하는 슬롯 존재 여부 */
    @Query("select count(v) > 0 from VisitSlot v "
            + "where v.property.id = :propertyId "
            + "and v.status in (com.jipsanim.reservation.slot.domain.VisitSlotStatus.OPEN, "
            + "                 com.jipsanim.reservation.slot.domain.VisitSlotStatus.RESERVED) "
            + "and v.startTime < :endTime and v.endTime > :startTime")
    boolean existsOverlap(@Param("propertyId") Long propertyId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);

    /** 마감: OPEN 일 때만 CLOSED 로 짧게 조건부 전환(락 역순 방지, plan D4/리뷰-3) */
    @Modifying(clearAutomatically = true)
    @Query("update VisitSlot v set v.status = com.jipsanim.reservation.slot.domain.VisitSlotStatus.CLOSED "
            + "where v.id = :id and v.status = com.jipsanim.reservation.slot.domain.VisitSlotStatus.OPEN")
    int closeIfOpen(@Param("id") Long id);
}
