package com.jipsanim.pricestandard.repository;

import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PriceStandardRepository extends JpaRepository<PriceStandard, Long> {

    /** 후보 승인 시 기존 ACTIVE 를 잠그고 교체 (plan D3) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PriceStandard p where p.sigunguCode = :sigunguCode "
            + "and p.propertyType = :propertyType and p.dealType = :dealType and p.status = 'ACTIVE'")
    Optional<PriceStandard> findActiveForUpdate(@Param("sigunguCode") String sigunguCode,
                                                @Param("propertyType") PropertyType propertyType,
                                                @Param("dealType") DealType dealType);

    /** 매물 검증용 조회 (Phase 5) */
    Optional<PriceStandard> findBySigunguCodeAndPropertyTypeAndDealTypeAndStatus(
            String sigunguCode, PropertyType propertyType, DealType dealType, PriceStandardStatus status);

    @Query("select p from PriceStandard p "
            + "where (:status is null or p.status = :status) "
            + "and (:sigunguCode is null or p.sigunguCode = :sigunguCode)")
    Page<PriceStandard> search(@Param("status") PriceStandardStatus status,
                               @Param("sigunguCode") String sigunguCode,
                               Pageable pageable);
}
