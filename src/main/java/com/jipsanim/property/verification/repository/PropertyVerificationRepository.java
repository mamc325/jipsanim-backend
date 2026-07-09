package com.jipsanim.property.verification.repository;

import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.verification.domain.PropertyVerification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PropertyVerificationRepository extends JpaRepository<PropertyVerification, Long> {

    @EntityGraph(attributePaths = "reasons")
    Optional<PropertyVerification> findWithReasonsById(Long id);

    @EntityGraph(attributePaths = "reasons")
    @Query("select v from PropertyVerification v "
            + "where (:status is null or v.status = :status) "
            + "and (:riskLevel is null or v.riskLevel = :riskLevel)")
    Page<PropertyVerification> search(@Param("status") VerificationStatus status,
                                      @Param("riskLevel") RiskLevel riskLevel,
                                      Pageable pageable);
}
