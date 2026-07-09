package com.jipsanim.external.log;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalApiCallLogRepository extends JpaRepository<ExternalApiCallLog, Long> {
}
