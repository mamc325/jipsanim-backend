package com.jipsanim.settlement.batch;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.settlement.dto.SettlementBatchRequest;
import com.jipsanim.settlement.dto.SettlementBatchResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 월별 정산 배치 수동 실행. /api/admin/** 은 SecurityConfig 에서 ADMIN 권한 요구.
 * 잡 실행이지만 3차 정산 배치는 예외적으로 동기 200 반환(별도 job 엔티티 없음, 리뷰 P0-3).
 */
@RestController
public class SettlementBatchController {

    private final SettlementBatchService batchService;

    public SettlementBatchController(SettlementBatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping("/api/admin/settlement-batch-jobs")
    public ApiResponse<SettlementBatchResult> run(@RequestBody(required = false) SettlementBatchRequest request) {
        YearMonth month = parseMonth(request);
        return ApiResponse.success(batchService.run(month));
    }

    private YearMonth parseMonth(SettlementBatchRequest request) {
        if (request == null || request.month() == null || request.month().isBlank()) {
            return null; // 전월
        }
        try {
            return YearMonth.parse(request.month());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "month 형식은 YYYY-MM 이어야 합니다.");
        }
    }
}
