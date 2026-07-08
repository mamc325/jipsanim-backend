package com.jipsanim.user.dto;

import com.jipsanim.user.domain.Role;

public record SignupResponse(Long userId, Role role) {
}
