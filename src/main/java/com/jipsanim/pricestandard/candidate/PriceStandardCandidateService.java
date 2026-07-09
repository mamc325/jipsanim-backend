package com.jipsanim.pricestandard.candidate;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.pricestandard.candidate.dto.CandidateApprovalResponse;
import com.jipsanim.pricestandard.domain.PriceStandard;
import com.jipsanim.pricestandard.domain.PriceStandardCandidate;
import com.jipsanim.pricestandard.domain.PriceStandardHistory;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.pricestandard.repository.PriceStandardHistoryRepository;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 시세 후보 승인/반려. 승인 시 기존 ACTIVE→EXPIRED, 신규 ACTIVE 반영, History 기록 (FR-035, plan D3).
 * 후보의 dataStatus 를 신규 기준에 상속(소표본 승인 허용).
 */
@Service
public class PriceStandardCandidateService {

    private static final String CHANGE_REASON = "CANDIDATE_APPROVED";

    private final PriceStandardCandidateRepository candidateRepository;
    private final PriceStandardRepository standardRepository;
    private final PriceStandardHistoryRepository historyRepository;

    public PriceStandardCandidateService(PriceStandardCandidateRepository candidateRepository,
                                         PriceStandardRepository standardRepository,
                                         PriceStandardHistoryRepository historyRepository) {
        this.candidateRepository = candidateRepository;
        this.standardRepository = standardRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public CandidateApprovalResponse approve(Long candidateId, Long adminUserId) {
        PriceStandardCandidate candidate = candidateRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        candidate.approve(adminUserId); // 멱등: PENDING 아니면 ALREADY_REVIEWED

        // 기존 ACTIVE 잠금 후 만료
        Optional<PriceStandard> existing = standardRepository.findActiveForUpdate(
                candidate.getSigunguCode(), candidate.getPropertyType(), candidate.getDealType());
        existing.ifPresent(PriceStandard::expire);
        // Hibernate 는 insert 를 update 보다 먼저 실행 → active_key 유니크 충돌 방지 위해 만료를 먼저 flush
        standardRepository.flush();

        PriceStandard activated = standardRepository.save(PriceStandard.activate(
                candidate.getSigunguCode(), candidate.getRegionName(), candidate.getPropertyType(),
                candidate.getDealType(), candidate.getCalcMinDeposit(), candidate.getCalcMaxDeposit(),
                candidate.getCalcMinMonthlyRent(), candidate.getCalcMaxMonthlyRent(),
                candidate.getSampleCount(), candidate.getDataStatus(), candidate.getSource()));

        historyRepository.save(PriceStandardHistory.record(activated, existing.orElse(null), adminUserId, CHANGE_REASON));

        return new CandidateApprovalResponse(candidate.getId(), activated.getId(), candidate.getStatus(),
                true, activated.getDataStatus());
    }

    @Transactional
    public void reject(Long candidateId, Long adminUserId) {
        PriceStandardCandidate candidate = candidateRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        candidate.reject(adminUserId);
    }
}
