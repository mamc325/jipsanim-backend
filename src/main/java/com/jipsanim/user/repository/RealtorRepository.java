package com.jipsanim.user.repository;

import com.jipsanim.user.domain.Realtor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RealtorRepository extends JpaRepository<Realtor, Long> {

    Optional<Realtor> findByUserId(Long userId);
}
