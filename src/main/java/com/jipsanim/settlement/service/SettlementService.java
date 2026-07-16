package com.jipsanim.settlement.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.settlement.domain.Settlement;
import com.jipsanim.settlement.dto.SettlementResponse;
import com.jipsanim.settlement.repository.SettlementRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.repository.RealtorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 정산 조회(중개사/관리자) + 확정/지급 상태전이. 상태전이는 멱등(재호출 200), payout 은 PENDING 이면 409.
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final RealtorRepository realtorRepository;
    private final com.jipsanim.outbox.publisher.OutboxEventPublisher outbox;

    public SettlementService(SettlementRepository settlementRepository, RealtorRepository realtorRepository,
                             com.jipsanim.outbox.publisher.OutboxEventPublisher outbox) {
        this.settlementRepository = settlementRepository;
        this.realtorRepository = realtorRepository;
        this.outbox = outbox;
    }

    /** /me/settlements — AuthUser.userId → Realtor 매핑 후 realtor_id 로 조회 (리뷰 P1). */
    @Transactional(readOnly = true)
    public List<SettlementResponse> mySettlements(Long userId, String month) {
        return realtorRepository.findByUserId(userId)
                .map(realtor -> settlementRepository.findMine(realtor.getId(), month).stream()
                        .map(s -> SettlementResponse.from(s, realtor.getBusinessName()))
                        .toList())
                .orElse(List.of());
    }

    /** /admin/settlements — month/realtor 선택 필터 + 페이지. 중개사명 배치 조회(N+1 금지). */
    @Transactional(readOnly = true)
    public Page<SettlementResponse> adminSettlements(String month, Long realtorId, Pageable pageable) {
        Page<Settlement> page = settlementRepository.search(month, realtorId, pageable);
        Map<Long, String> names = realtorNames(page.getContent().stream().map(Settlement::getRealtorId).toList());
        return page.map(s -> SettlementResponse.from(s, names.get(s.getRealtorId())));
    }

    private Map<Long, String> realtorNames(List<Long> realtorIds) {
        if (realtorIds.isEmpty()) {
            return Map.of();
        }
        return realtorRepository.findAllById(realtorIds).stream()
                .collect(Collectors.toMap(Realtor::getId, Realtor::getBusinessName));
    }

    private String realtorName(Long realtorId) {
        return realtorRepository.findById(realtorId).map(Realtor::getBusinessName).orElse(null);
    }

    /** 확정: PENDING→CONFIRMED. 이미 CONFIRMED/PAID → 현재 상태 반환(멱등 200). */
    @Transactional
    public SettlementResponse confirm(Long settlementId) {
        Settlement s = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (s.isPending()) {
            s.confirm();
        }
        // CONFIRMED / PAID → 그대로 현재 상태 반환
        return SettlementResponse.from(s, realtorName(s.getRealtorId()));
    }

    /** 지급: CONFIRMED→PAID. 이미 PAID → 현재 상태 반환(멱등 200). PENDING → 409. */
    @Transactional
    public SettlementResponse payout(Long settlementId) {
        Settlement s = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (s.isPaid()) {
            return SettlementResponse.from(s, realtorName(s.getRealtorId())); // 멱등
        }
        if (s.isPending()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "확정되지 않은 정산은 지급할 수 없습니다.");
        }
        s.payout(); // CONFIRMED → PAID
        Realtor realtor = realtorRepository.findById(s.getRealtorId()).orElse(null);
        if (realtor != null) {
            outbox.append("SETTLEMENT", s.getId(), "SETTLEMENT_PAID",
                    "SETTLEMENT_PAID:" + s.getId(),
                    java.util.Map.of("recipientUserId", realtor.getUser().getId(),
                            "settlementId", s.getId(),
                            "settlementMonth", s.getSettlementMonth(),
                            "payoutAmount", s.getPayoutAmount()));
        }
        return SettlementResponse.from(s, realtor == null ? null : realtor.getBusinessName());
    }
}
