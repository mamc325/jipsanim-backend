package com.jipsanim.common.security;

import com.jipsanim.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long validitySeconds;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret()));
        this.validitySeconds = properties.accessTokenValiditySeconds();
    }

    public String createAccessToken(Long userId, Role role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validitySeconds * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 토큰 검증 후 principal 복원. 실패 시 JwtException 계열 예외를 던진다. */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
        return new AuthUser(userId, role);
    }

    public long getValiditySeconds() {
        return validitySeconds;
    }
}
