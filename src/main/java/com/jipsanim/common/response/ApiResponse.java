package com.jipsanim.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jipsanim.common.error.ErrorCode;

/**
 * 전 API 공통 응답 래퍼.
 * <pre>
 * 성공: { "success": true,  "data": {...}, "error": null }
 * 실패: { "success": false, "data": null,  "error": { "code": "...", "message": "..." } }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 반환 데이터가 없는 성공 응답 (무인자 success() 는 record 접근자와 충돌하므로 ok 로 명명) */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message));
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return error(code, code.defaultMessage());
    }
}
