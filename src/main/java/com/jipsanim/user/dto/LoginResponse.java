package com.jipsanim.user.dto;

import com.jipsanim.user.domain.Role;

public record LoginResponse(String accessToken, Role role, long expiresIn) {
}
