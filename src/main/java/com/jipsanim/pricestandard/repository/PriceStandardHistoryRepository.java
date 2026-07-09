package com.jipsanim.pricestandard.repository;

import com.jipsanim.pricestandard.domain.PriceStandardHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceStandardHistoryRepository extends JpaRepository<PriceStandardHistory, Long> {
}
