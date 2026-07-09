package com.jipsanim.external.log;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalApiCallLogRepository extends JpaRepository<ExternalApiCallLog, Long> {

    @Query("select l from ExternalApiCallLog l "
            + "where (:apiType is null or l.apiType = :apiType) "
            + "and (:success is null or l.success = :success)")
    Page<ExternalApiCallLog> search(@Param("apiType") ApiType apiType,
                                    @Param("success") Boolean success,
                                    Pageable pageable);
}
