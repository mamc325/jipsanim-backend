package com.jipsanim.settlement.cancel;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.settlement.dto.ReservationCancellationResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CancellationController {

    private final CancellationService cancellationService;

    public CancellationController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @PostMapping("/api/reservations/{reservationId}/cancellation")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<ReservationCancellationResponse> cancel(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long reservationId) {
        return ApiResponse.success(cancellationService.cancel(reservationId, authUser.userId()));
    }
}
