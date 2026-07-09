package com.jipsanim.common.error;

import com.jipsanim.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("business error(5xx): {}", code, e);
        } else {
            log.warn("business error: {} - {}", code, e.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
    }

    // 동시 승인 등에서 유니크 제약(active_key) 위반 → 500 대신 409로 (경쟁에서 밀린 요청)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("data integrity violation (treated as conflict): {}",
                e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(ErrorCode.ALREADY_REVIEWED.httpStatus())
                .body(ApiResponse.error(ErrorCode.ALREADY_REVIEWED));
    }

    // @PreAuthorize 등 메서드 보안 거부는 컨트롤러 어드바이스로 전파되므로 여기서 403 처리
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(ApiResponse.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private static String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}
