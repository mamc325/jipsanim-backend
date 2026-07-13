package com.jipsanim.settlement.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.settlement.dto.SettlementResponse;
import com.jipsanim.settlement.service.SettlementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 정산 조회/확정/지급. /api/admin/** 은 SecurityConfig 에서 ADMIN 권한 요구.
 * 상태전이는 명사형 서브리소스 POST(confirmation/payout) 컨벤션.
 */
@RestController
@RequestMapping("/api/admin/settlements")
public class SettlementAdminController {

    private final SettlementService settlementService;

    public SettlementAdminController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    public ApiResponse<Page<SettlementResponse>> list(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long realtorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(settlementService.adminSettlements(month, realtorId, pageable));
    }

    @PostMapping("/{settlementId}/confirmation")
    public ApiResponse<SettlementResponse> confirm(@PathVariable Long settlementId) {
        return ApiResponse.success(settlementService.confirm(settlementId));
    }

    @PostMapping("/{settlementId}/payout")
    public ApiResponse<SettlementResponse> payout(@PathVariable Long settlementId) {
        return ApiResponse.success(settlementService.payout(settlementId));
    }
}
