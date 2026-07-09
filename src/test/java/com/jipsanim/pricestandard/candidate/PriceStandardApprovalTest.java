package com.jipsanim.pricestandard.candidate;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.pricestandard.domain.CalcMethod;
import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PriceStandardApprovalTest {

    @Autowired
    PriceStandardCandidateService service;
    @Autowired
    PriceStandardCandidateRepository candidateRepository;
    @Autowired
    PriceStandardRepository standardRepository;

    private Long saveCandidate(String sigungu, DealType dealType, DataStatus dataStatus, long minDeposit, long maxDeposit) {
        PriceStandardCandidate candidate = PriceStandardCandidate.create(sigungu, "테스트구", PropertyType.OFFICETEL,
                dealType, minDeposit, maxDeposit, null, null, CalcMethod.IQR, 50, dataStatus,
                "MOLIT_OFFICETEL_RENT", "2026-07", 1L);
        return candidateRepository.save(candidate).getId();
    }

    @Test
    @DisplayName("후보 승인 시 신규 ACTIVE 생성, 기존 ACTIVE 는 EXPIRED 되고 ACTIVE 는 1건만 유지된다")
    void approveSwapsActive() {
        String sigungu = "11111";
        Long first = saveCandidate(sigungu, DealType.JEONSE, DataStatus.SUFFICIENT, 5_000_000, 50_000_000);
        service.approve(first, 99L);

        Long second = saveCandidate(sigungu, DealType.JEONSE, DataStatus.SUFFICIENT, 6_000_000, 60_000_000);
        service.approve(second, 99L);

        Optional<PriceStandard> active = standardRepository.findBySigunguCodeAndPropertyTypeAndDealTypeAndStatus(
                sigungu, PropertyType.OFFICETEL, DealType.JEONSE, PriceStandardStatus.ACTIVE);
        assertThat(active).isPresent();
        assertThat(active.get().getStatus()).isEqualTo(PriceStandardStatus.ACTIVE);
        assertThat(active.get().getMinDeposit()).isEqualTo(6_000_000); // 두 번째로 교체됨
    }

    @Test
    @DisplayName("이미 처리된 후보 재승인은 ALREADY_REVIEWED")
    void reapproveRejected() {
        Long candidate = saveCandidate("22222", DealType.JEONSE, DataStatus.SUFFICIENT, 5_000_000, 50_000_000);
        service.approve(candidate, 99L);

        assertThatThrownBy(() -> service.approve(candidate, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("소표본(INSUFFICIENT_DATA) 후보도 승인되며 기준에 dataStatus 상속")
    void insufficientApprovable() {
        Long candidate = saveCandidate("33333", DealType.JEONSE, DataStatus.INSUFFICIENT_DATA, 5_000_000, 50_000_000);

        var result = service.approve(candidate, 99L);

        assertThat(result.activated()).isTrue();
        assertThat(result.dataStatus()).isEqualTo(DataStatus.INSUFFICIENT_DATA);
    }
}
