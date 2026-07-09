package com.jipsanim.property.verification.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectionRequest(@NotBlank String reason) {
}
