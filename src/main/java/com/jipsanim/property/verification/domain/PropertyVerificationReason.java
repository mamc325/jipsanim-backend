package com.jipsanim.property.verification.domain;

import com.jipsanim.property.domain.ReasonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "property_verification_reason")
public class PropertyVerificationReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verification_id", nullable = false)
    private PropertyVerification verification;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 40)
    private ReasonType reasonType;

    @Column(length = 500)
    private String message;

    protected PropertyVerificationReason() {
    }

    PropertyVerificationReason(PropertyVerification verification, ReasonType reasonType, String message) {
        this.verification = verification;
        this.reasonType = reasonType;
        this.message = message;
    }

    public ReasonType getReasonType() {
        return reasonType;
    }

    public String getMessage() {
        return message;
    }
}
