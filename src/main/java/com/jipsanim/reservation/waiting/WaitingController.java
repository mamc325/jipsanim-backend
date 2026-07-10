package com.jipsanim.reservation.waiting;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.reservation.waiting.dto.WaitingEntryResponse;
import com.jipsanim.reservation.waiting.dto.WaitingStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WaitingController {

    private final WaitingService waitingService;

    public WaitingController(WaitingService waitingService) {
        this.waitingService = waitingService;
    }

    @PostMapping("/api/visit-slots/{slotId}/waiting")
    @PreAuthorize("hasRole('USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WaitingEntryResponse> enter(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long slotId) {
        return ApiResponse.success(waitingService.enter(slotId, authUser.userId()));
    }

    @GetMapping("/api/visit-slots/{slotId}/waiting/me")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<WaitingStatusResponse> myStatus(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long slotId) {
        return ApiResponse.success(waitingService.myStatus(slotId, authUser.userId()));
    }
}
