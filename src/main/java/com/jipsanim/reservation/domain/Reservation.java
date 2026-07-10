package com.jipsanim.reservation.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 방문 예약. active_reservation_key 는 앱 관리 컬럼(1차 active_key 패턴):
 * status IN (PENDING_PAYMENT, CONFIRMED) 이면 visit_slot_id, else null → 슬롯당 활성 1건 UNIQUE 보장.
 */
@Entity
@Table(name = "reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_active", columnNames = "active_reservation_key"),
        indexes = {
                @Index(name = "idx_reservation_user", columnList = "user_id, status"),
                @Index(name = "idx_reservation_slot", columnList = "visit_slot_id"),
                @Index(name = "idx_reservation_sweep", columnList = "status, expires_at")
        })
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "visit_slot_id", nullable = false)
    private Long visitSlotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "active_reservation_key")
    private Long activeReservationKey;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    protected Reservation() {
    }

    private Reservation(Long userId, Long propertyId, Long visitSlotId, LocalDateTime expiresAt) {
        this.userId = userId;
        this.propertyId = propertyId;
        this.visitSlotId = visitSlotId;
        this.status = ReservationStatus.PENDING_PAYMENT;
        this.activeReservationKey = visitSlotId; // 활성 → slot 점유
        this.expiresAt = expiresAt;
        this.reservedAt = LocalDateTime.now();
    }

    public static Reservation create(Long userId, Long propertyId, Long visitSlotId, LocalDateTime expiresAt) {
        return new Reservation(userId, propertyId, visitSlotId, expiresAt);
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        // active_reservation_key 유지(CONFIRMED 도 활성) → 슬롯당 확정 1건
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.activeReservationKey = null; // 슬롯 재예약 허용
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
        this.activeReservationKey = null;
        this.cancelledAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return status == ReservationStatus.PENDING_PAYMENT;
    }

    public boolean isConfirmed() {
        return status == ReservationStatus.CONFIRMED;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return isPending() && expiresAt != null && now.isAfter(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getVisitSlotId() {
        return visitSlotId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
