package com.jipsanim.pricestandard.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 운영 시세 기준. (sigungu, propertyType, dealType) 당 ACTIVE 는 1건 —
 * active_key(ACTIVE 일 때만 값) UNIQUE 로 DB 레벨 보장. (plan D3)
 */
@Entity
@Table(name = "price_standard",
        uniqueConstraints = @UniqueConstraint(name = "uk_price_standard_active", columnNames = "active_key"))
public class PriceStandard extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sigungu_code", nullable = false, length = 5)
    private String sigunguCode;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 20)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 20)
    private DealType dealType;

    @Column(name = "min_deposit")
    private Long minDeposit;

    @Column(name = "max_deposit")
    private Long maxDeposit;

    @Column(name = "min_monthly_rent")
    private Long minMonthlyRent;

    @Column(name = "max_monthly_rent")
    private Long maxMonthlyRent;

    @Column(name = "sample_count")
    private int sampleCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false, length = 20)
    private DataStatus dataStatus;

    @Column(length = 50)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PriceStandardStatus status;

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    // ACTIVE 일 때만 sigungu:type:deal, 아니면 null → UNIQUE 로 ACTIVE 유일성 보장
    @Column(name = "active_key", length = 64)
    private String activeKey;

    protected PriceStandard() {
    }

    private PriceStandard(String sigunguCode, String regionName, PropertyType propertyType, DealType dealType,
                          Long minDeposit, Long maxDeposit, Long minMonthlyRent, Long maxMonthlyRent,
                          int sampleCount, DataStatus dataStatus, String source) {
        this.sigunguCode = sigunguCode;
        this.regionName = regionName;
        this.propertyType = propertyType;
        this.dealType = dealType;
        this.minDeposit = minDeposit;
        this.maxDeposit = maxDeposit;
        this.minMonthlyRent = minMonthlyRent;
        this.maxMonthlyRent = maxMonthlyRent;
        this.sampleCount = sampleCount;
        this.dataStatus = dataStatus;
        this.source = source;
        this.status = PriceStandardStatus.ACTIVE;
        this.effectiveFrom = LocalDateTime.now();
        this.activeKey = activeKey(sigunguCode, propertyType, dealType);
    }

    public static PriceStandard activate(String sigunguCode, String regionName, PropertyType propertyType,
                                         DealType dealType, Long minDeposit, Long maxDeposit, Long minMonthlyRent,
                                         Long maxMonthlyRent, int sampleCount, DataStatus dataStatus, String source) {
        return new PriceStandard(sigunguCode, regionName, propertyType, dealType, minDeposit, maxDeposit,
                minMonthlyRent, maxMonthlyRent, sampleCount, dataStatus, source);
    }

    public static String activeKey(String sigunguCode, PropertyType propertyType, DealType dealType) {
        return "%s:%s:%s".formatted(sigunguCode, propertyType, dealType);
    }

    /** 기존 ACTIVE 를 만료: 상태 EXPIRED, effectiveTo=now, active_key=null(유니크 해제). */
    public void expire() {
        this.status = PriceStandardStatus.EXPIRED;
        this.effectiveTo = LocalDateTime.now();
        this.activeKey = null;
    }

    public boolean isInsufficient() {
        return dataStatus == DataStatus.INSUFFICIENT_DATA;
    }

    public Long getId() {
        return id;
    }

    public String getSigunguCode() {
        return sigunguCode;
    }

    public String getRegionName() {
        return regionName;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public DealType getDealType() {
        return dealType;
    }

    public Long getMinDeposit() {
        return minDeposit;
    }

    public Long getMaxDeposit() {
        return maxDeposit;
    }

    public Long getMinMonthlyRent() {
        return minMonthlyRent;
    }

    public Long getMaxMonthlyRent() {
        return maxMonthlyRent;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public DataStatus getDataStatus() {
        return dataStatus;
    }

    public String getSource() {
        return source;
    }

    public PriceStandardStatus getStatus() {
        return status;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }
}
