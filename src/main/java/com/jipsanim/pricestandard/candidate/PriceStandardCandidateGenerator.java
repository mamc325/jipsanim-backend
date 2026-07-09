package com.jipsanim.pricestandard.candidate;

import com.jipsanim.external.molit.OfficetelRentTransaction;
import com.jipsanim.pricestandard.config.PriceStandardProperties;
import com.jipsanim.pricestandard.domain.CalcMethod;
import com.jipsanim.pricestandard.domain.DataStatus;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.pricestandard.stats.PriceRange;
import com.jipsanim.pricestandard.stats.RangeCalculator;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.PropertyType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 수집 표본을 (시군구, 거래유형)별로 그룹핑해 RangeCalculator 로 정상 범위를 산출하고
 * PriceStandardCandidate(PENDING) 를 생성한다. 표본 부족 시 INSUFFICIENT_DATA. (FR-033, 034)
 */
@Service
public class PriceStandardCandidateGenerator {

    private static final String SOURCE = "MOLIT_OFFICETEL_RENT";

    private final RangeCalculator rangeCalculator;
    private final PriceStandardCandidateRepository candidateRepository;
    private final PriceStandardProperties properties;

    public PriceStandardCandidateGenerator(RangeCalculator rangeCalculator,
                                           PriceStandardCandidateRepository candidateRepository,
                                           PriceStandardProperties properties) {
        this.rangeCalculator = rangeCalculator;
        this.candidateRepository = candidateRepository;
        this.properties = properties;
    }

    @Transactional
    public int generate(List<OfficetelRentTransaction> transactions, String calculatedMonth, Long batchJobId) {
        CalcMethod method = CalcMethod.valueOf(properties.calcMethod());
        Map<Group, List<OfficetelRentTransaction>> grouped = transactions.stream()
                .collect(Collectors.groupingBy(t -> new Group(t.sigunguCode(), t.dealType())));

        int created = 0;
        for (Map.Entry<Group, List<OfficetelRentTransaction>> entry : grouped.entrySet()) {
            candidateRepository.save(toCandidate(entry.getKey(), entry.getValue(), method, calculatedMonth, batchJobId));
            created++;
        }
        return created;
    }

    private PriceStandardCandidate toCandidate(Group group, List<OfficetelRentTransaction> txns, CalcMethod method,
                                               String calculatedMonth, Long batchJobId) {
        PriceRange depositRange = rangeCalculator.calculate(
                txns.stream().mapToLong(OfficetelRentTransaction::deposit).toArray(), method);

        Long minRent = null;
        Long maxRent = null;
        if (group.dealType() == DealType.MONTHLY_RENT) {
            PriceRange rentRange = rangeCalculator.calculate(
                    txns.stream().mapToLong(OfficetelRentTransaction::monthlyRent).toArray(), method);
            minRent = rentRange.min();
            maxRent = rentRange.max();
        }

        int sampleCount = txns.size();
        DataStatus dataStatus = sampleCount < properties.minSampleCount()
                ? DataStatus.INSUFFICIENT_DATA : DataStatus.SUFFICIENT;
        String regionName = txns.get(0).regionName();

        return PriceStandardCandidate.create(group.sigunguCode(), regionName, PropertyType.OFFICETEL,
                group.dealType(), depositRange.min(), depositRange.max(), minRent, maxRent, method,
                sampleCount, dataStatus, SOURCE, calculatedMonth, batchJobId);
    }

    private record Group(String sigunguCode, DealType dealType) {
    }
}
