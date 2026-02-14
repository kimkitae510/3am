package com.threeam.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소셜 가입은 이메일 제공이 선택 동의라 null일 수 있다(특히 카카오 기본 앱).
    @Column(unique = true, length = 100)
    private String email;

    // 소셜 가입 계정은 비밀번호가 없다. BCrypt matches는 null 해시에 false를 돌려주므로
    // 이메일 로그인 경로는 안전하지만, 비밀번호 변경은 명시적으로 거부한다(UserService).
    @Column
    private String password;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    // (provider, provider_id) 유니크 — 같은 카카오/네이버 계정으로 계정이 두 개 생기지 않는다.
    @Column(name = "provider_id", length = 100)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 탈퇴는 소프트 딜리트. 기록(사연/결제)을 남기는 정책과 이메일 재사용 차단을 위해 물리 삭제하지 않는다.
    @Column
    private LocalDateTime deletedAt;

    @Builder
    private User(String email, String password, Role role,
                 AuthProvider provider, String providerId) {
        this.email = email;
        this.password = password;
        this.role = role;
        this.provider = provider == null ? AuthProvider.EMAIL : provider;
        this.providerId = providerId;
    }

    public boolean hasPassword() {
        return password != null;
    }

    public boolean isGuest() {
        return provider == AuthProvider.GUEST;
    }

    // 게스트 승격 — 새 계정을 만들지 않고 이 행의 신원을 교체한다(사연, 쿼터 기록이 그대로 이어진다).
    public void linkEmail(String email, String encodedPassword) {
        this.email = email;
        this.password = encodedPassword;
        this.provider = AuthProvider.EMAIL;
        this.providerId = null;
    }

    public void linkSocial(AuthProvider provider, String providerId, String email) {
        this.email = email;
        this.provider = provider;
        this.providerId = providerId;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void withdraw(LocalDateTime when) {
        this.deletedAt = when;
    }

    public boolean isWithdrawn() {
        return deletedAt != null;
    }
}
