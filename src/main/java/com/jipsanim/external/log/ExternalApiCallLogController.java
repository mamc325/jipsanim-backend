package com.jipsanim.external.log;

import com.jipsanim.common.response.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/external-api-call-logs")
public class ExternalApiCallLogController {

    private final ExternalApiCallLogRepository repository;

    public ExternalApiCallLogController(ExternalApiCallLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<Page<ExternalApiCallLogResponse>> list(
            @RequestParam(required = false) ApiType apiType,
            @RequestParam(required = false) Boolean success,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ExternalApiCallLogResponse> page = repository.search(apiType, success, pageable)
                .map(ExternalApiCallLogResponse::from);
        return ApiResponse.success(page);
    }
}
