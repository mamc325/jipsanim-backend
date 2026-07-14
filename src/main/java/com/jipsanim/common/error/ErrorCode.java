package com.jipsanim.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 전반의 오류 코드. contracts/api-contract.md 의 에러 코드와 정렬한다.
 */
public enum ErrorCode {

    // 400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    EMAIL_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 이메일입니다."),

    // 401
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_OWNER(HttpStatus.FORBIDDEN, "리소스의 소유자가 아닙니다."),

    // 404
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),

    // 409
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 허용되지 않는 작업입니다."),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 처리된 건입니다."),
    CONFLICT(HttpStatus.CONFLICT, "동시 요청 경쟁으로 처리에 실패했습니다."),
    ALREADY_WAITING(HttpStatus.CONFLICT, "이미 대기열에 있습니다."),
    ALREADY_GRANTED(HttpStatus.CONFLICT, "이미 예약권을 보유하고 있습니다."),

    // 422
    INSUFFICIENT_DATA_APPROVAL_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "표본이 부족한 후보는 승인할 수 없습니다."),

    // 500 / 502 / 503
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    EXTERNAL_ADDRESS_API_ERROR(HttpStatus.BAD_GATEWAY, "주소 조회 서비스 호출에 실패했습니다."),
    EXTERNAL_REAL_ESTATE_API_ERROR(HttpStatus.BAD_GATEWAY, "실거래가 조회 서비스 호출에 실패했습니다."),
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "검색 서비스를 일시적으로 사용할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
