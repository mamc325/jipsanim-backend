package com.jipsanim.reservation.slot.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import com.jipsanim.property.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "visit_slot",
        uniqueConstraints = @UniqueConstraint(name = "uk_visit_slot_time", columnNames = {"property_id", "start_time"}),
        indexes = @Index(name = "idx_visit_slot_property", columnList = "property_id, status"))
public class VisitSlot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisitSlotStatus status;

    protected VisitSlot() {
    }

    private VisitSlot(Property property, LocalDateTime startTime, LocalDateTime endTime) {
        this.property = property;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = VisitSlotStatus.OPEN;
    }

    public static VisitSlot create(Property property, LocalDateTime startTime, LocalDateTime endTime) {
        return new VisitSlot(property, startTime, endTime);
    }

    public boolean isReserved() {
        return status == VisitSlotStatus.RESERVED;
    }

    public boolean isOpen() {
        return status == VisitSlotStatus.OPEN;
    }

    /** 결제 확정 시 OPEN→RESERVED */
    public void reserve() {
        this.status = VisitSlotStatus.RESERVED;
    }

    public boolean isOwnedBy(Long realtorId) {
        return property != null && property.getRealtor().getId().equals(realtorId);
    }

    public Long getId() {
        return id;
    }

    public Property getProperty() {
        return property;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public VisitSlotStatus getStatus() {
        return status;
    }
}
