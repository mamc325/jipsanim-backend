package com.jipsanim.pricestandard.repository;

import com.jipsanim.pricestandard.domain.CandidateStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceStandardCandidateRepository extends JpaRepository<PriceStandardCandidate, Long> {

    @Query("select c from PriceStandardCandidate c where (:status is null or c.status = :status)")
    Page<PriceStandardCandidate> search(@Param("status") CandidateStatus status, Pageable pageable);
}
