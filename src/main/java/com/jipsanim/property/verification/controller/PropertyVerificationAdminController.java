package com.jipsanim.property.verification.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.domain.RiskLevel;
import com.jipsanim.property.domain.VerificationStatus;
import com.jipsanim.property.verification.dto.RejectionRequest;
import com.jipsanim.property.verification.dto.VerificationDecisionResponse;
import com.jipsanim.property.verification.dto.VerificationSummaryResponse;
import com.jipsanim.property.verification.service.PropertyVerificationAdminService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/property-verifications")
public class PropertyVerificationAdminController {

    private final PropertyVerificationAdminService adminService;

    public PropertyVerificationAdminController(PropertyVerificationAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public ApiResponse<Page<VerificationSummaryResponse>> list(
            @RequestParam(required = false) VerificationStatus status,
            @RequestParam(required = false) RiskLevel riskLevel,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(adminService.list(status, riskLevel, pageable));
    }

    @PostMapping("/{verificationId}/approval")
    public ApiResponse<VerificationDecisionResponse> approve(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long verificationId) {
        return ApiResponse.success(adminService.approve(verificationId, authUser.userId()));
    }

    @PostMapping("/{verificationId}/rejection")
    public ApiResponse<VerificationDecisionResponse> reject(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long verificationId,
            @Valid @RequestBody RejectionRequest request) {
        return ApiResponse.success(adminService.reject(verificationId, authUser.userId(), request.reason()));
    }
}
