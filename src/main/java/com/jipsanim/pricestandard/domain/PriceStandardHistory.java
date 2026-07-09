package com.jipsanim.pricestandard.domain;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "price_standard_history")
public class PriceStandardHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_standard_id")
    private Long priceStandardId;

    @Column(name = "sigungu_code", length = 5)
    private String sigunguCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", length = 20)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", length = 20)
    private DealType dealType;

    @Column(name = "prev_min_deposit")
    private Long prevMinDeposit;
    @Column(name = "prev_max_deposit")
    private Long prevMaxDeposit;
    @Column(name = "prev_min_monthly_rent")
    private Long prevMinMonthlyRent;
    @Column(name = "prev_max_monthly_rent")
    private Long prevMaxMonthlyRent;

    @Column(name = "new_min_deposit")
    private Long newMinDeposit;
    @Column(name = "new_max_deposit")
    private Long newMaxDeposit;
    @Column(name = "new_min_monthly_rent")
    private Long newMinMonthlyRent;
    @Column(name = "new_max_monthly_rent")
    private Long newMaxMonthlyRent;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "change_reason", length = 255)
    private String changeReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PriceStandardHistory() {
    }

    private PriceStandardHistory(Long priceStandardId, String sigunguCode, PropertyType propertyType,
                                 DealType dealType, PriceStandard previous, PriceStandard current,
                                 Long changedBy, String changeReason) {
        this.priceStandardId = priceStandardId;
        this.sigunguCode = sigunguCode;
        this.propertyType = propertyType;
        this.dealType = dealType;
        if (previous != null) {
            this.prevMinDeposit = previous.getMinDeposit();
            this.prevMaxDeposit = previous.getMaxDeposit();
            this.prevMinMonthlyRent = previous.getMinMonthlyRent();
            this.prevMaxMonthlyRent = previous.getMaxMonthlyRent();
        }
        this.newMinDeposit = current.getMinDeposit();
        this.newMaxDeposit = current.getMaxDeposit();
        this.newMinMonthlyRent = current.getMinMonthlyRent();
        this.newMaxMonthlyRent = current.getMaxMonthlyRent();
        this.changedBy = changedBy;
        this.changeReason = changeReason;
        this.createdAt = LocalDateTime.now();
    }

    public static PriceStandardHistory record(PriceStandard current, PriceStandard previous,
                                              Long changedBy, String changeReason) {
        return new PriceStandardHistory(current.getId(), current.getSigunguCode(), current.getPropertyType(),
                current.getDealType(), previous, current, changedBy, changeReason);
    }

    public Long getId() {
        return id;
    }
}
