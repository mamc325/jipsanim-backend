package com.jipsanim.pricestandard.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "price_standard_candidate", indexes = {
        @Index(name = "idx_candidate_status", columnList = "status, sigungu_code")
})
public class PriceStandardCandidate extends BaseTimeEntity {

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

    @Column(name = "calc_min_deposit")
    private Long calcMinDeposit;

    @Column(name = "calc_max_deposit")
    private Long calcMaxDeposit;

    @Column(name = "calc_min_monthly_rent")
    private Long calcMinMonthlyRent;

    @Column(name = "calc_max_monthly_rent")
    private Long calcMaxMonthlyRent;

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_method", length = 20)
    private CalcMethod calcMethod;

    @Column(name = "sample_count")
    private int sampleCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false, length = 20)
    private DataStatus dataStatus;

    @Column(length = 50)
    private String source;

    @Column(name = "calculated_month", length = 7)
    private String calculatedMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CandidateStatus status;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "batch_job_id")
    private Long batchJobId;

    protected PriceStandardCandidate() {
    }

    private PriceStandardCandidate(String sigunguCode, String regionName, PropertyType propertyType, DealType dealType,
                                   Long calcMinDeposit, Long calcMaxDeposit, Long calcMinMonthlyRent,
                                   Long calcMaxMonthlyRent, CalcMethod calcMethod, int sampleCount,
                                   DataStatus dataStatus, String source, String calculatedMonth, Long batchJobId) {
        this.sigunguCode = sigunguCode;
        this.regionName = regionName;
        this.propertyType = propertyType;
        this.dealType = dealType;
        this.calcMinDeposit = calcMinDeposit;
        this.calcMaxDeposit = calcMaxDeposit;
        this.calcMinMonthlyRent = calcMinMonthlyRent;
        this.calcMaxMonthlyRent = calcMaxMonthlyRent;
        this.calcMethod = calcMethod;
        this.sampleCount = sampleCount;
        this.dataStatus = dataStatus;
        this.source = source;
        this.calculatedMonth = calculatedMonth;
        this.batchJobId = batchJobId;
        this.status = CandidateStatus.PENDING;
    }

    public static PriceStandardCandidate create(String sigunguCode, String regionName, PropertyType propertyType,
                                                DealType dealType, Long calcMinDeposit, Long calcMaxDeposit,
                                                Long calcMinMonthlyRent, Long calcMaxMonthlyRent, CalcMethod calcMethod,
                                                int sampleCount, DataStatus dataStatus, String source,
                                                String calculatedMonth, Long batchJobId) {
        return new PriceStandardCandidate(sigunguCode, regionName, propertyType, dealType, calcMinDeposit,
                calcMaxDeposit, calcMinMonthlyRent, calcMaxMonthlyRent, calcMethod, sampleCount, dataStatus,
                source, calculatedMonth, batchJobId);
    }

    public void approve(Long adminUserId) {
        requirePending();
        this.status = CandidateStatus.APPROVED;
        this.reviewedBy = adminUserId;
        this.reviewedAt = LocalDateTime.now();
    }

    public void reject(Long adminUserId) {
        requirePending();
        this.status = CandidateStatus.REJECTED;
        this.reviewedBy = adminUserId;
        this.reviewedAt = LocalDateTime.now();
    }

    private void requirePending() {
        if (status != CandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_REVIEWED);
        }
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

    public Long getCalcMinDeposit() {
        return calcMinDeposit;
    }

    public Long getCalcMaxDeposit() {
        return calcMaxDeposit;
    }

    public Long getCalcMinMonthlyRent() {
        return calcMinMonthlyRent;
    }

    public Long getCalcMaxMonthlyRent() {
        return calcMaxMonthlyRent;
    }

    public CalcMethod getCalcMethod() {
        return calcMethod;
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

    public String getCalculatedMonth() {
        return calculatedMonth;
    }

    public CandidateStatus getStatus() {
        return status;
    }
}
