package com.jipsanim.search.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * ES 매물 문서. createIndex=false 로 Spring Data 자동 생성(nori 미적용)을 막고,
 * settings/mapping 은 PropertyIndexBootstrap 이 JSON 으로 생성한다(리뷰 P1).
 * _id = propertyId 문자열(색인 upsert 멱등). 정렬은 숫자 propertyId 필드 사용.
 */
@Document(indexName = "property", createIndex = false)
public class PropertyDocument {

    @Id
    private String id;          // = propertyId (문자열, ES _id)
    private Long propertyId;    // 정렬/tie-breaker용 숫자
    private String title;
    private String description;
    private String roadAddress;
    private String regionName;
    private String nearStation;
    private String sigunguCode;
    private String dealType;
    private String propertyType;
    private String status;
    private String primaryImageUrl;
    private Long deposit;
    private Long monthlyRent;
    private Double area;
    private Integer roomCount;
    private Long realtorId;
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
    private Instant createdAt;

    protected PropertyDocument() {
    }

    private PropertyDocument(Long propertyId) {
        this.id = String.valueOf(propertyId);
        this.propertyId = propertyId;
    }

    public static PropertyDocument of(Long propertyId) {
        return new PropertyDocument(propertyId);
    }

    public PropertyDocument title(String v) { this.title = v; return this; }
    public PropertyDocument description(String v) { this.description = v; return this; }
    public PropertyDocument roadAddress(String v) { this.roadAddress = v; return this; }
    public PropertyDocument regionName(String v) { this.regionName = v; return this; }
    public PropertyDocument nearStation(String v) { this.nearStation = v; return this; }
    public PropertyDocument sigunguCode(String v) { this.sigunguCode = v; return this; }
    public PropertyDocument dealType(String v) { this.dealType = v; return this; }
    public PropertyDocument propertyType(String v) { this.propertyType = v; return this; }
    public PropertyDocument status(String v) { this.status = v; return this; }
    public PropertyDocument primaryImageUrl(String v) { this.primaryImageUrl = v; return this; }
    public PropertyDocument deposit(Long v) { this.deposit = v; return this; }
    public PropertyDocument monthlyRent(Long v) { this.monthlyRent = v; return this; }
    public PropertyDocument area(Double v) { this.area = v; return this; }
    public PropertyDocument roomCount(Integer v) { this.roomCount = v; return this; }
    public PropertyDocument realtorId(Long v) { this.realtorId = v; return this; }
    public PropertyDocument createdAt(Instant v) { this.createdAt = v; return this; }

    public String getId() { return id; }
    public Long getPropertyId() { return propertyId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getRoadAddress() { return roadAddress; }
    public String getRegionName() { return regionName; }
    public String getNearStation() { return nearStation; }
    public String getSigunguCode() { return sigunguCode; }
    public String getDealType() { return dealType; }
    public String getPropertyType() { return propertyType; }
    public String getStatus() { return status; }
    public String getPrimaryImageUrl() { return primaryImageUrl; }
    public Long getDeposit() { return deposit; }
    public Long getMonthlyRent() { return monthlyRent; }
    public Double getArea() { return area; }
    public Integer getRoomCount() { return roomCount; }
    public Long getRealtorId() { return realtorId; }
    public Instant getCreatedAt() { return createdAt; }
}
