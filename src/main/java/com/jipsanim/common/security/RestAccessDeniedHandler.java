package com.jipsanim.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 인증됐으나 권한 부족 → 403 JSON(ApiResponse) */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        RestAuthenticationEntryPoint.writeError(response, ErrorCode.FORBIDDEN, objectMapper);
    }
}
