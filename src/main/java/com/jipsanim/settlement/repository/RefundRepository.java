package com.jipsanim.settlement.repository;

import com.jipsanim.settlement.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPaymentId(Long paymentId);

    boolean existsByPaymentId(Long paymentId);
}
