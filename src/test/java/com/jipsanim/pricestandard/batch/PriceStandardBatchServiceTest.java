package com.jipsanim.pricestandard.batch;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.external.molit.OfficetelRentTransaction;
import com.jipsanim.external.molit.RealEstateClient;
import com.jipsanim.pricestandard.candidate.PriceStandardCandidateGenerator;
import com.jipsanim.pricestandard.config.PriceStandardProperties;
import com.jipsanim.pricestandard.domain.BatchStatus;
import com.jipsanim.pricestandard.domain.PriceStandardBatchJob;
import com.jipsanim.pricestandard.repository.PriceStandardBatchJobRepository;
import com.jipsanim.property.domain.DealType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceStandardBatchServiceTest {

    private RealEstateClient realEstateClient;
    private PriceStandardBatchJobRepository batchJobRepository;
    private PriceStandardCandidateGenerator candidateGenerator;
    private PriceStandardBatchService service;

    @BeforeEach
    void setUp() {
        realEstateClient = mock(RealEstateClient.class);
        batchJobRepository = mock(PriceStandardBatchJobRepository.class);
        candidateGenerator = mock(PriceStandardCandidateGenerator.class);
        when(batchJobRepository.save(any(PriceStandardBatchJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // months=1, concurrency=4
        var properties = new PriceStandardProperties(30, 1, 4, "IQR");
        service = new PriceStandardBatchService(realEstateClient, batchJobRepository, candidateGenerator, properties);
    }

    @Test
    @DisplayName("일부 지역 실패 시 전체를 실패시키지 않고 PARTIAL_FAILED 로 집계한다")
    void partialFailure() {
        when(realEstateClient.fetch(eq("11680"), any())).thenReturn(List.of(
                new OfficetelRentTransaction("11680", "강남구", DealType.JEONSE, 220_000_000L, 0, new BigDecimal("29.87"), 2026, 4)));
        when(realEstateClient.fetch(eq("11650"), any()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_REAL_ESTATE_API_ERROR));

        BatchCollectResult result = service.run(1, List.of("11680", "11650"), "TEST");

        assertThat(result.totalRequestCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(BatchStatus.PARTIAL_FAILED);
        assertThat(result.transactions()).hasSize(1);
    }

    @Test
    @DisplayName("모든 지역 성공 시 SUCCESS")
    void allSuccess() {
        when(realEstateClient.fetch(any(), any())).thenReturn(List.of(
                new OfficetelRentTransaction("11680", "강남구", DealType.MONTHLY_RENT, 10_000_000L, 700_000L,
                        new BigDecimal("33.0"), 2026, 4)));

        BatchCollectResult result = service.run(1, List.of("11680", "11650"), "TEST");

        assertThat(result.status()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.transactions()).hasSize(2);
    }
}
