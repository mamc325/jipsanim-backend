package com.jipsanim.property.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.dto.PropertyCreateRequest;
import com.jipsanim.property.dto.PropertyDetailResponse;
import com.jipsanim.property.dto.PropertyMutationResponse;
import com.jipsanim.property.dto.PropertyUpdateRequest;
import com.jipsanim.property.service.PropertyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('REALTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PropertyMutationResponse> create(
            @AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody PropertyCreateRequest request) {
        return ApiResponse.success(propertyService.create(authUser.userId(), request));
    }

    @PatchMapping("/{propertyId}")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<PropertyMutationResponse> update(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long propertyId,
            @Valid @RequestBody PropertyUpdateRequest request) {
        return ApiResponse.success(propertyService.update(authUser.userId(), propertyId, request));
    }

    @DeleteMapping("/{propertyId}")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long propertyId) {
        propertyService.delete(authUser.userId(), propertyId);
        return ApiResponse.ok();
    }

    @GetMapping("/{propertyId}")
    public ApiResponse<PropertyDetailResponse> detail(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long propertyId) {
        return ApiResponse.success(propertyService.getDetail(authUser, propertyId));
    }
}
