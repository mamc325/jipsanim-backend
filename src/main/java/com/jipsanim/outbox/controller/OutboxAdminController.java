package com.jipsanim.outbox.controller;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.dto.OutboxEventResponse;
import com.jipsanim.outbox.service.OutboxAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbox 모니터링/재처리. /api/admin/** 은 SecurityConfig 에서 ADMIN 권한 요구.
 */
@RestController
@RequestMapping("/api/admin/outbox-events")
public class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    public OutboxAdminController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    @GetMapping
    public ApiResponse<Page<OutboxEventResponse>> list(
            @RequestParam(required = false) OutboxStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(outboxAdminService.list(status, pageable));
    }

    @PostMapping("/{outboxEventId}/reprocess")
    public ApiResponse<OutboxEventResponse> reprocess(@PathVariable Long outboxEventId) {
        return ApiResponse.success(outboxAdminService.reprocess(outboxEventId));
    }
}
