package com.jipsanim.notification.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.common.security.AuthUser;
import com.jipsanim.notification.dto.NotificationResponse;
import com.jipsanim.notification.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 알림 조회/읽음. 인증된 모든 역할(USER/REALTOR/ADMIN) 사용.
 */
@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/me/notifications")
    public ApiResponse<Page<NotificationResponse>> myNotifications(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "false") boolean unread,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(notificationService.myNotifications(authUser.userId(), unread, pageable));
    }

    /** 읽음 처리(부수효과 없는 플래그 갱신 → PATCH). */
    @PatchMapping("/api/notifications/{notificationId}")
    public ApiResponse<NotificationResponse> markRead(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long notificationId) {
        return ApiResponse.success(notificationService.markRead(notificationId, authUser.userId()));
    }
}
