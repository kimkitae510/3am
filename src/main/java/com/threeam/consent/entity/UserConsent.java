package com.threeam.consent.entity;

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

// 동의 이력. "누가 언제 어떤 판(version)의 문서에 동의했나"의 증빙이라 수정/삭제 없이 쌓기만 한다.
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consent_type", nullable = false, length = 30)
    private ConsentType type;

    @Column(name = "doc_version", nullable = false, length = 20)
    private String docVersion;

    // PURCHASE_POLICY만 주문과 묶인다. 나머지는 null.
    @Column(name = "order_id", length = 64)
    private String orderId;

    @CreationTimestamp
    @Column(name = "agreed_at", nullable = false, updatable = false)
    private LocalDateTime agreedAt;

    @Builder
    private UserConsent(Long userId, ConsentType type, String docVersion, String orderId) {
        this.userId = userId;
        this.type = type;
        this.docVersion = docVersion;
        this.orderId = orderId;
    }
}
