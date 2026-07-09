package com.jipsanim.pricestandard.candidate;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.pricestandard.candidate.dto.CandidateApprovalResponse;
import com.jipsanim.pricestandard.candidate.dto.CandidateResponse;
import com.jipsanim.pricestandard.candidate.dto.PriceStandardResponse;
import com.jipsanim.pricestandard.domain.CandidateStatus;
import com.jipsanim.pricestandard.domain.PriceStandardStatus;
import com.jipsanim.pricestandard.repository.PriceStandardCandidateRepository;
import com.jipsanim.pricestandard.repository.PriceStandardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class PriceStandardAdminController {

    private final PriceStandardCandidateService candidateService;
    private final PriceStandardCandidateRepository candidateRepository;
    private final PriceStandardRepository standardRepository;

    public PriceStandardAdminController(PriceStandardCandidateService candidateService,
                                        PriceStandardCandidateRepository candidateRepository,
                                        PriceStandardRepository standardRepository) {
        this.candidateService = candidateService;
        this.candidateRepository = candidateRepository;
        this.standardRepository = standardRepository;
    }

    @GetMapping("/price-standard-candidates")
    public ApiResponse<Page<CandidateResponse>> candidates(
            @RequestParam(required = false) CandidateStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(candidateRepository.search(status, pageable).map(CandidateResponse::from));
    }

    @PostMapping("/price-standard-candidates/{candidateId}/approval")
    public ApiResponse<CandidateApprovalResponse> approve(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long candidateId) {
        return ApiResponse.success(candidateService.approve(candidateId, authUser.userId()));
    }

    @PostMapping("/price-standard-candidates/{candidateId}/rejection")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long candidateId) {
        candidateService.reject(candidateId, authUser.userId());
        return ApiResponse.ok();
    }

    @GetMapping("/price-standards")
    public ApiResponse<Page<PriceStandardResponse>> standards(
            @RequestParam(required = false) PriceStandardStatus status,
            @RequestParam(required = false) String sigunguCode,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(standardRepository.search(status, sigunguCode, pageable)
                .map(PriceStandardResponse::from));
    }
}
