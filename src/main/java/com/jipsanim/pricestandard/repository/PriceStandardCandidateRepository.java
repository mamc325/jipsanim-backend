package com.jipsanim.pricestandard.repository;

import com.jipsanim.pricestandard.domain.CandidateStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PriceStandardCandidateRepository extends JpaRepository<PriceStandardCandidate, Long> {

    @Query("select c from PriceStandardCandidate c where (:status is null or c.status = :status)")
    Page<PriceStandardCandidate> search(@Param("status") CandidateStatus status, Pageable pageable);

    /** 승인/반려 시 후보 행을 잠가 동시 처리(TOCTOU) 경쟁 방지 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PriceStandardCandidate c where c.id = :id")
    Optional<PriceStandardCandidate> findByIdForUpdate(@Param("id") Long id);
}
