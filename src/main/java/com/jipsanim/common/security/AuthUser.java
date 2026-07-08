package com.jipsanim.common.security;

import com.jipsanim.user.domain.Role;

/**
 * 인증된 사용자 principal. JWT 클레임에서 복원되며 컨트롤러에서 @AuthenticationPrincipal 로 주입받는다.
 */
public record AuthUser(Long userId, Role role) {
}
