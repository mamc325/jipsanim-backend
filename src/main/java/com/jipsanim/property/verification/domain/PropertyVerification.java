package com.jipsanim.property.verification.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.ReasonType;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "property_verification", indexes = {
        @Index(name = "idx_verification_status_risk", columnList = "status, risk_level"),
        @Index(name = "idx_verification_property", columnList = "property_id")
})
public class PropertyVerification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 10)
    private RiskLevel riskLevel;

    @Column(name = "rejected_reason", length = 500)
    private String rejectedReason;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @OneToMany(mappedBy = "verification", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PropertyVerificationReason> reasons = new ArrayList<>();

    protected PropertyVerification() {
    }

    private PropertyVerification(Long propertyId, Long requestedBy, VerificationStatus status, RiskLevel riskLevel) {
        this.propertyId = propertyId;
        this.requestedBy = requestedBy;
        this.status = status;
        this.riskLevel = riskLevel;
    }

    public static PropertyVerification create(Long propertyId, Long requestedBy,
                                              VerificationStatus status, RiskLevel riskLevel) {
        return new PropertyVerification(propertyId, requestedBy, status, riskLevel);
    }

    public void addReason(ReasonType reasonType, String message) {
        reasons.add(new PropertyVerificationReason(this, reasonType, message));
    }

    public void approve(Long adminUserId) {
        requireReviewable();
        this.status = VerificationStatus.APPROVED;
        this.reviewedBy = adminUserId;
        this.reviewedAt = LocalDateTime.now();
    }

    public void reject(Long adminUserId, String reason) {
        requireReviewable();
        this.status = VerificationStatus.REJECTED;
        this.rejectedReason = reason;
        this.reviewedBy = adminUserId;
        this.reviewedAt = LocalDateTime.now();
    }

    private void requireReviewable() {
        if (status == VerificationStatus.APPROVED || status == VerificationStatus.REJECTED) {
            throw new BusinessException(ErrorCode.ALREADY_REVIEWED);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public List<PropertyVerificationReason> getReasons() {
        return reasons;
    }
}
