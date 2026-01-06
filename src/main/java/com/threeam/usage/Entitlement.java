package com.threeam.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 결제로 지급된 이용권. 일일 무료 한도를 다 쓴 뒤에 여기서 차감된다.
// usage 도메인에 두는 이유: 이용권은 "쓸 수 있는 횟수"라는 사용량 개념의 확장이고,
// 이 방향이어야 의존이 payment → usage 한 방향으로 유지된다(usage는 결제를 모른다).
// (payment_id, kind) 유니크가 이중 지급을 DB 수준에서 막는다 — 승인 재시도, 웹훅 재전송,
// 재동기화가 겹쳐 지급 로직이 몇 번을 다시 돌아도 종류당 한 번만 생긴다.
// (묶음 상품은 한 결제가 대화, 진단 이용권을 각각 지급하므로 결제당 행이 여러 개일 수 있다.)
@Entity
@Table(name = "entitlements", uniqueConstraints =
        @UniqueConstraint(name = "uk_entitlements_payment_kind", columnNames = {"payment_id", "kind"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private UsageKind kind;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // 환불로 회수된 시각. 회수된 이용권은 차감, 잔여 계산 모두에서 제외된다.
    // used_count를 건드리지 않고 따로 두는 이유: "몇 회 쓰고 환불했는지"가 분쟁 근거로 남아야 한다.
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Entitlement(Long userId, UsageKind kind, int totalCount, Long paymentId) {
        this.userId = userId;
        this.kind = kind;
        this.totalCount = totalCount;
        this.usedCount = 0;
        this.paymentId = paymentId;
    }

    public int remainingCount() {
        return Math.max(0, totalCount - usedCount);
    }
}
