package com.jipsanim.reservation.slot.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.reservation.slot.dto.VisitSlotCreateRequest;
import com.jipsanim.reservation.slot.dto.VisitSlotResponse;
import com.jipsanim.reservation.slot.service.VisitSlotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class VisitSlotController {

    private final VisitSlotService visitSlotService;

    public VisitSlotController(VisitSlotService visitSlotService) {
        this.visitSlotService = visitSlotService;
    }

    @PostMapping("/api/properties/{propertyId}/visit-slots")
    @PreAuthorize("hasRole('REALTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VisitSlotResponse> create(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long propertyId,
            @Valid @RequestBody VisitSlotCreateRequest request) {
        return ApiResponse.success(visitSlotService.create(authUser.userId(), propertyId, request));
    }

    @GetMapping("/api/properties/{propertyId}/visit-slots")
    public ApiResponse<List<VisitSlotResponse>> list(@PathVariable Long propertyId) {
        return ApiResponse.success(visitSlotService.list(propertyId));
    }

    @DeleteMapping("/api/visit-slots/{slotId}")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<Void> close(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long slotId) {
        visitSlotService.close(authUser.userId(), slotId);
        return ApiResponse.ok();
    }
}
