package com.jipsanim.user.dto;

import com.jipsanim.user.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 50) String nickname,
        @NotNull Role role,
        // role=REALTOR 일 때 필수 (서비스에서 검증)
        String businessName,
        String phone) {
}
