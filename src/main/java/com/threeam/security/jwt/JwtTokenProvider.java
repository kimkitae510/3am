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

    // 토큰 용도 구분. 수명 긴 refresh 토큰을 access처럼 API 인증에 쓰지 못하게 막는 근거.
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    // 게스트 → 기존 소셜 계정 전환 확인용 티켓. 인가 코드는 1회용이라 "경고 후 재시도"가 불가능해서,
    // 소셜 인증을 통과한 신원(provider+providerId)을 짧게 서명해 두 번째 호출의 증거로 쓴다.
    public static final String TYPE_OAUTH_SWITCH = "oauth_switch";

    private static final long OAUTH_SWITCH_VALIDITY_MS = 5 * 60 * 1000;

    private final SecretKey key;
    private final long accessValidityMs;
    private final long refreshValidityMs;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessValidityMs = properties.getAccessTokenValiditySeconds() * 1000;
        this.refreshValidityMs = properties.getRefreshTokenValiditySeconds() * 1000;
    }

    public String generateAccessToken(Long userId, Role role) {
        return build(userId, role.name(), TYPE_ACCESS, accessValidityMs);
    }

    public String generateRefreshToken(Long userId) {
        return build(userId, null, TYPE_REFRESH, refreshValidityMs);
    }

    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(getType(token));
    }

    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(getType(token));
    }

    public String generateOAuthSwitchTicket(String provider, String providerId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("oauth-switch")
                .claim("typ", TYPE_OAUTH_SWITCH)
                .claim("provider", provider)
                .claim("providerId", providerId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + OAUTH_SWITCH_VALIDITY_MS))
                .signWith(key)
                .compact();
    }

    public boolean isOAuthSwitchTicket(String token) {
        return TYPE_OAUTH_SWITCH.equals(getType(token));
    }

    public String getStringClaim(String token, String name) {
        return parse(token).get(name, String.class);
    }

    private String getType(String token) {
        return parse(token).get("typ", String.class);
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

    private String build(Long userId, String role, String type, long validityMs) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("typ", type)
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
