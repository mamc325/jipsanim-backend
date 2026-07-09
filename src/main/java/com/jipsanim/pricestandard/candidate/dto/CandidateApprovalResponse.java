package com.jipsanim.pricestandard.candidate.dto;

import com.jipsanim.pricestandard.domain.CandidateStatus;
import com.jipsanim.pricestandard.domain.DataStatus;

public record CandidateApprovalResponse(
        Long candidateId,
        Long priceStandardId,
        CandidateStatus status,
        boolean activated,
        DataStatus dataStatus) {
}
