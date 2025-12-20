package com.threeam.security.jwt;

import com.threeam.global.config.JwtProperties;
import com.threeam.user.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessValidityMs;
    private final long refreshValidityMs;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessValidityMs = properties.getAccessTokenValiditySeconds() * 1000;
        this.refreshValidityMs = properties.getRefreshTokenValiditySeconds() * 1000;
    }

    public String generateAccessToken(Long userId, Role role) {
        return build(userId, role.name(), accessValidityMs);
    }

    public String generateRefreshToken(Long userId) {
        return build(userId, null, refreshValidityMs);
    }

    public boolean validateToken(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    public String getRole(String token) {
        return parse(token).get("role", String.class);
    }

    public long getIssuedAtMillis(String token) {
        return parse(token).getIssuedAt().getTime();
    }

    public long getAccessValidityMillis() {
        return accessValidityMs;
    }

    private String build(Long userId, String role, long validityMs) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + validityMs))
                .signWith(key);
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.compact();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
