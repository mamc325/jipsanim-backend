package com.jipsanim.property.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.dto.MyPropertyResponse;
import com.jipsanim.property.service.PropertyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 중개사 본인 매물 목록(/me 패턴). PropertyController 는 /api/properties 매핑이라 별도 컨트롤러.
 */
@RestController
public class MyPropertyController {

    private final PropertyService propertyService;

    public MyPropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @GetMapping("/api/me/properties")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<Page<MyPropertyResponse>> myProperties(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) PropertyStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(propertyService.myProperties(authUser.userId(), status, pageable));
    }
}
