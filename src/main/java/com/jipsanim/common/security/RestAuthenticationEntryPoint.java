package com.jipsanim.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** 미인증 요청 → 401 JSON(ApiResponse) */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeError(response, ErrorCode.UNAUTHORIZED, objectMapper);
    }

    static void writeError(HttpServletResponse response, ErrorCode code, ObjectMapper mapper) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.error(code));
    }
}
