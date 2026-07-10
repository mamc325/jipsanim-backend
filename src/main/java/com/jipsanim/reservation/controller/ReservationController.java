package com.jipsanim.reservation.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.reservation.dto.ReservationCreateResponse;
import com.jipsanim.reservation.dto.ReservationSummaryResponse;
import com.jipsanim.reservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/api/visit-slots/{slotId}/reservations")
    @PreAuthorize("hasRole('USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationCreateResponse> create(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long slotId) {
        return ApiResponse.success(reservationService.create(authUser.userId(), slotId));
    }

    @GetMapping("/api/me/reservations")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<List<ReservationSummaryResponse>> myReservations(
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(reservationService.myReservations(authUser.userId()));
    }
}
