package com.jipsanim.property.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.property.dto.PropertyCreateRequest;
import com.jipsanim.property.dto.PropertyDetailResponse;
import com.jipsanim.property.dto.PropertyDetailResult;
import com.jipsanim.property.dto.PropertyMutationResponse;
import com.jipsanim.property.dto.PropertySearchCondition;
import com.jipsanim.property.dto.PropertySummaryResponse;
import com.jipsanim.property.dto.PropertyUpdateRequest;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.property.service.PropertyService;
import com.jipsanim.property.verification.dto.SubmissionResponse;
import com.jipsanim.property.verification.service.PropertyVerificationService;
import com.jipsanim.property.popular.PropertyDetailCache;
import com.jipsanim.property.view.ViewCountService;
import com.jipsanim.property.view.ViewerKeyResolver;
import com.jipsanim.user.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
    private final PropertyVerificationService verificationService;
    private final PropertyRepository propertyRepository;
    private final ViewCountService viewCountService;
    private final ViewerKeyResolver viewerKeyResolver;
    private final PropertyDetailCache detailCache;

    public PropertyController(PropertyService propertyService,
                             PropertyVerificationService verificationService,
                             PropertyRepository propertyRepository,
                             ViewCountService viewCountService,
                             ViewerKeyResolver viewerKeyResolver,
                             PropertyDetailCache detailCache) {
        this.propertyService = propertyService;
        this.verificationService = verificationService;
        this.propertyRepository = propertyRepository;
        this.viewCountService = viewCountService;
        this.viewerKeyResolver = viewerKeyResolver;
        this.detailCache = detailCache;
    }

    @GetMapping
    public ApiResponse<Page<PropertySummaryResponse>> search(
            PropertySearchCondition condition,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(propertyRepository.search(condition, pageable));
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

    @PostMapping("/{propertyId}/submission")
    @PreAuthorize("hasRole('REALTOR')")
    public ApiResponse<SubmissionResponse> submit(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long propertyId) {
        return ApiResponse.success(verificationService.submit(authUser.userId(), propertyId));
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
            @PathVariable Long propertyId,
            HttpServletRequest request) {
        // 읽기 정책(P1): REALTOR/ADMIN(소유자 가능성·권위) 캐시 우회 → DB 직조회. anonymous/USER 는 cache-first.
        boolean bypassCache = authUser != null
                && (authUser.role() == Role.REALTOR || authUser.role() == Role.ADMIN);

        PropertyDetailResponse response;
        boolean countable;
        PropertyDetailResponse hit = bypassCache ? null : detailCache.get(propertyId);
        if (hit != null) {
            response = hit;          // 캐시엔 ACTIVE 공개표현만 존재 → 집계 대상
            countable = true;
        } else {
            PropertyDetailResult result = propertyService.getDetail(authUser, propertyId);
            response = result.response();
            countable = result.countablePublicAccess();
            if (!bypassCache && countable) {
                detailCache.put(propertyId, response); // anonymous/USER miss 경로만 캐시 워밍
            }
        }
        // ACTIVE 공개표현이면 cache hit/miss 무관 집계(best-effort, dedup).
        if (countable) {
            viewCountService.record(propertyId, viewerKeyResolver.resolve(authUser, request));
        }
        return ApiResponse.success(response);
    }
}
