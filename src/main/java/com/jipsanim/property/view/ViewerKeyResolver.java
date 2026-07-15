package com.jipsanim.property.view;

import com.jipsanim.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 조회수 dedup 용 viewerKey 도출(6차). 인증 사용자는 `u:{userId}`,
 * 비인증은 클라이언트 IP `ip:{addr}`. XFF 스푸핑 방지: trust-proxy 일 때만 X-Forwarded-For 사용.
 */
@Component
public class ViewerKeyResolver {

    private final boolean trustProxy;

    public ViewerKeyResolver(ViewCountProperties properties) {
        this.trustProxy = properties.trustProxy();
    }

    public String resolve(AuthUser authUser, HttpServletRequest request) {
        if (authUser != null) {
            return "u:" + authUser.userId();
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
