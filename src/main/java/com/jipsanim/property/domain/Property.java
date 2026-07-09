package com.jipsanim.property.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import com.jipsanim.user.domain.Realtor;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "property", indexes = {
        @Index(name = "idx_property_search", columnList = "status, sigungu_code, deal_type, property_type"),
        @Index(name = "idx_property_realtor", columnList = "realtor_id")
})
public class Property extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "realtor_id", nullable = false)
    private Realtor realtor;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "road_address", length = 255)
    private String roadAddress;

    @Column(name = "bjdong_code", length = 10)
    private String bjdongCode;

    @Column(name = "sigungu_code", length = 5)
    private String sigunguCode;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "near_station", length = 100)
    private String nearStation;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 20)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 20)
    private DealType dealType;

    private Long deposit;

    @Column(name = "monthly_rent")
    private Long monthlyRent;

    @Column(precision = 7, scale = 2)
    private BigDecimal area;

    @Column(name = "room_count")
    private Integer roomCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropertyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 20)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 10)
    private RiskLevel riskLevel;

    @OneToMany(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PropertyImage> images = new ArrayList<>();

    protected Property() {
    }

    private Property(Realtor realtor, String title, String description, String roadAddress, String bjdongCode,
                     String sigunguCode, String regionName, String nearStation, PropertyType propertyType,
                     DealType dealType, Long deposit, Long monthlyRent, BigDecimal area, Integer roomCount) {
        this.realtor = realtor;
        this.title = title;
        this.description = description;
        this.roadAddress = roadAddress;
        this.bjdongCode = bjdongCode;
        this.sigunguCode = sigunguCode;
        this.regionName = regionName;
        this.nearStation = nearStation;
        this.propertyType = propertyType;
        this.dealType = dealType;
        this.deposit = deposit;
        this.monthlyRent = monthlyRent;
        this.area = area;
        this.roomCount = roomCount;
        this.status = PropertyStatus.DRAFT;
    }

    public static Property createDraft(Realtor realtor, String title, String description, String roadAddress,
                                       String bjdongCode, String regionName, String nearStation,
                                       PropertyType propertyType, DealType dealType, Long deposit, Long monthlyRent,
                                       BigDecimal area, Integer roomCount) {
        return new Property(realtor, title, description, roadAddress, bjdongCode,
                deriveSigungu(bjdongCode), regionName, nearStation, propertyType, dealType,
                deposit, monthlyRent, area, roomCount);
    }

    private static String deriveSigungu(String bjdongCode) {
        return bjdongCode != null && bjdongCode.length() >= 5 ? bjdongCode.substring(0, 5) : bjdongCode;
    }

    public void addImage(String imageUrl, boolean primary, int sortOrder) {
        images.add(new PropertyImage(this, imageUrl, primary, sortOrder));
    }

    public void replaceImages() {
        images.clear();
    }

    /** null 이 아닌 필드만 부분 수정. bjdongCode 변경 시 sigunguCode 재파생. */
    public void update(String title, String description, String roadAddress, String bjdongCode, String regionName,
                       String nearStation, DealType dealType, Long deposit, Long monthlyRent, BigDecimal area,
                       Integer roomCount) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (roadAddress != null) this.roadAddress = roadAddress;
        if (bjdongCode != null) {
            this.bjdongCode = bjdongCode;
            this.sigunguCode = deriveSigungu(bjdongCode);
        }
        if (regionName != null) this.regionName = regionName;
        if (nearStation != null) this.nearStation = nearStation;
        if (dealType != null) this.dealType = dealType;
        if (deposit != null) this.deposit = deposit;
        if (monthlyRent != null) this.monthlyRent = monthlyRent;
        if (area != null) this.area = area;
        if (roomCount != null) this.roomCount = roomCount;
    }

    public void softDelete() {
        this.status = PropertyStatus.DELETED;
    }

    public boolean isOwnedBy(Long realtorId) {
        return realtor != null && realtor.getId().equals(realtorId);
    }

    public Long getId() {
        return id;
    }

    public Realtor getRealtor() {
        return realtor;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public String getBjdongCode() {
        return bjdongCode;
    }

    public String getSigunguCode() {
        return sigunguCode;
    }

    public String getRegionName() {
        return regionName;
    }

    public String getNearStation() {
        return nearStation;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public DealType getDealType() {
        return dealType;
    }

    public Long getDeposit() {
        return deposit;
    }

    public Long getMonthlyRent() {
        return monthlyRent;
    }

    public BigDecimal getArea() {
        return area;
    }

    public Integer getRoomCount() {
        return roomCount;
    }

    public PropertyStatus getStatus() {
        return status;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public List<PropertyImage> getImages() {
        return images;
    }
}
