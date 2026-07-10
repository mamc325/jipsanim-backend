package com.jipsanim.reservation.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.reservation.dto.PaymentConfirmationResponse;
import com.jipsanim.reservation.dto.PaymentFailureResponse;
import com.jipsanim.reservation.service.PaymentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/payments/{paymentId}/confirmation")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<PaymentConfirmationResponse> confirm(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long paymentId) {
        return ApiResponse.success(paymentService.confirm(paymentId, authUser.userId()));
    }

    @PostMapping("/api/payments/{paymentId}/failure")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<PaymentFailureResponse> fail(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long paymentId) {
        return ApiResponse.success(paymentService.fail(paymentId, authUser.userId()));
    }
}
