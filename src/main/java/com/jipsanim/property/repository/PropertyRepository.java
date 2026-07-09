package com.jipsanim.property.repository;

import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long>, PropertySearchRepository {

    @EntityGraph(attributePaths = {"images", "realtor"})
    Optional<Property> findWithImagesById(Long id);

    /** 중복 의심: 동일 주소·거래유형의 다른(삭제 아님) 매물 존재 여부 */
    @Query("select count(p) > 0 from Property p "
            + "where p.roadAddress = :roadAddress and p.dealType = :dealType "
            + "and p.id <> :propertyId and p.status <> com.jipsanim.property.domain.PropertyStatus.DELETED")
    boolean existsDuplicate(@Param("roadAddress") String roadAddress,
                            @Param("dealType") DealType dealType,
                            @Param("propertyId") Long propertyId);
}
