package com.threeam.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    // 토큰 원문이 아니라 SHA-256 해시(64자 hex)를 저장한다 — DB가 유출돼도 토큰 재사용 불가.
    // 컬럼 길이는 기존 512를 유지(스키마 변경 불필요, 해시는 64자라 여유롭게 들어감).
    @Column(nullable = false, length = 512)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    private RefreshToken(Long userId, String token, LocalDateTime expiresAt) {
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public void rotate(String token, LocalDateTime expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean matches(String token) {
        return this.token.equals(token);
    }
}
