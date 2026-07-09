package com.jipsanim.property.verification.engine;

import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.repository.PropertyRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 매물 자동 검증 엔진: ACTIVE 시세 기준·중복 여부를 사전 조회해 컨텍스트를 만들고
 * 모든 규칙을 실행한 뒤 RiskScorer 로 riskLevel/status 를 산정한다.
 */
@Component
public class PropertyVerificationEngine {

    private final List<VerificationRule> rules;
    private final RiskScorer riskScorer;
    private final PriceStandardRepository priceStandardRepository;
    private final PropertyRepository propertyRepository;

    public PropertyVerificationEngine(List<VerificationRule> rules, RiskScorer riskScorer,
                                      PriceStandardRepository priceStandardRepository,
                                      PropertyRepository propertyRepository) {
        this.rules = rules;
        this.riskScorer = riskScorer;
        this.priceStandardRepository = priceStandardRepository;
        this.propertyRepository = propertyRepository;
    }

    public VerificationResult verify(Property property) {
        Optional<PriceStandard> standard = priceStandardRepository
                .findBySigunguCodeAndPropertyTypeAndDealTypeAndStatus(
                        property.getSigunguCode(), property.getPropertyType(), property.getDealType(),
                        PriceStandardStatus.ACTIVE);
        boolean duplicate = property.getRoadAddress() != null
                && propertyRepository.existsDuplicate(property.getRoadAddress(), property.getDealType(),
                property.getId() == null ? -1L : property.getId());

        VerificationContext context = new VerificationContext(property, standard, duplicate);
        List<VerificationFinding> findings = rules.stream()
                .map(rule -> rule.evaluate(context))
                .flatMap(Optional::stream)
                .toList();

        RiskScorer.RiskAssessment assessment = riskScorer.assess(findings);
        return new VerificationResult(assessment.riskLevel(), assessment.status(), findings);
    }
}
