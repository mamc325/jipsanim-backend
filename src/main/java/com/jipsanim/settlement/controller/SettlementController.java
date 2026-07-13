package com.jipsanim.settlement.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.settlement.dto.SettlementResponse;
import com.jipsanim.settlement.service.SettlementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 중개사 본인 정산 조회(month 선택). */
    @GetMapping("/api/me/settlements")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<List<SettlementResponse>> mySettlements(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String month) {
        return ApiResponse.success(settlementService.mySettlements(authUser.userId(), month));
    }
}
