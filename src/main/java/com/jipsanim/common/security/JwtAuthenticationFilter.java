package com.jipsanim.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer 토큰을 파싱해 SecurityContext 에 인증을 채운다.
 * 토큰이 없거나 유효하지 않으면 인증 없이 통과시키고, 접근 제어는 SecurityConfig 가 담당한다.
 * (SecurityConfig 에서 직접 인스턴스화하므로 스프링 빈으로 등록하지 않는다 — 중복 필터 등록 방지)
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                AuthUser authUser = tokenProvider.parse(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        authUser, null, List.of(new SimpleGrantedAuthority(authUser.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // 유효하지 않은 토큰 → 미인증 상태로 진행. EntryPoint 가 401 처리.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
