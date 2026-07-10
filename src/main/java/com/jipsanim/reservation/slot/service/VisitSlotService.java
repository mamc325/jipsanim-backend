package com.jipsanim.reservation.slot.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.dto.VisitSlotCreateRequest;
import com.jipsanim.reservation.slot.dto.VisitSlotResponse;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.repository.RealtorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 방문 슬롯 등록/조회/마감. 생성 검증: ACTIVE 매물·미래·start<end·시간 겹침 금지(항목 6).
 * 마감은 OPEN 일 때만 조건부 update + Redis 정리(락 역순 방지, plan D4).
 */
@Service
public class VisitSlotService {

    private final VisitSlotRepository slotRepository;
    private final PropertyRepository propertyRepository;
    private final RealtorRepository realtorRepository;
    private final WaitingQueueService waitingQueueService;

    public VisitSlotService(VisitSlotRepository slotRepository, PropertyRepository propertyRepository,
                            RealtorRepository realtorRepository, WaitingQueueService waitingQueueService) {
        this.slotRepository = slotRepository;
        this.propertyRepository = propertyRepository;
        this.realtorRepository = realtorRepository;
        this.waitingQueueService = waitingQueueService;
    }

    @Transactional
    public VisitSlotResponse create(Long userId, Long propertyId, VisitSlotCreateRequest req) {
        Realtor realtor = currentRealtor(userId);
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!property.getRealtor().getId().equals(realtor.getId())) {
            throw new BusinessException(ErrorCode.NOT_OWNER);
        }
        if (property.getStatus() != PropertyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "ACTIVE 매물에만 방문 슬롯을 등록할 수 있습니다.");
        }
        validateTime(req, property.getId());

        VisitSlot slot = slotRepository.save(VisitSlot.create(property, req.startTime(), req.endTime()));
        return VisitSlotResponse.from(slot);
    }

    @Transactional(readOnly = true)
    public List<VisitSlotResponse> list(Long propertyId) {
        return slotRepository.findByPropertyIdOrderByStartTimeAsc(propertyId).stream()
                .map(VisitSlotResponse::from)
                .toList();
    }

    @Transactional
    public void close(Long userId, Long slotId) {
        Realtor realtor = currentRealtor(userId);
        VisitSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!slot.isOwnedBy(realtor.getId())) {
            throw new BusinessException(ErrorCode.NOT_OWNER);
        }
        if (slot.isReserved()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "예약 확정된 슬롯은 마감할 수 없습니다.");
        }
        int closed = slotRepository.closeIfOpen(slotId); // OPEN 일 때만 CLOSED
        if (closed == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 마감/처리된 슬롯입니다.");
        }
        // Redis 대기열/토큰 정리. 진행 중 PENDING 예약 정리는 예약 도메인 도입(Phase 5) 시 연계.
        waitingQueueService.cleanupSlot(slotId);
    }

    private void validateTime(VisitSlotCreateRequest req, Long propertyId) {
        if (!req.startTime().isBefore(req.endTime())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "시작 시각은 종료 시각보다 앞서야 합니다.");
        }
        if (!req.startTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "과거 시각에는 방문 슬롯을 등록할 수 없습니다.");
        }
        if (slotRepository.existsOverlap(propertyId, req.startTime(), req.endTime())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "기존 방문 슬롯과 시간이 겹칩니다.");
        }
    }

    private Realtor currentRealtor(Long userId) {
        return realtorRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "중개사만 수행할 수 있습니다."));
    }
}
