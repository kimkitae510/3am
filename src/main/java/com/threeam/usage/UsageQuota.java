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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 유저, 종류당 행 하나로 오늘 사용량을 센다(단일 행 lazy reset).
// 날짜별 행을 쌓지 않는다 — quotaDate가 오늘이 아니면 "리셋 대상"이라는 뜻이고,
// 리셋과 증가는 리포지토리의 원자 upsert 한 문장에서 함께 처리된다(자정 레이스 방지).
@Entity
@Table(name = "usage_quota", uniqueConstraints =
        @UniqueConstraint(name = "uk_usage_quota_user_kind", columnNames = {"user_id", "kind"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private UsageKind kind;

    // 마지막으로 사용량을 센 날짜(KST). 오늘과 다르면 카운터는 무효(리셋 대상)다.
    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Column(name = "used_count", nullable = false)
    private int usedCount;
}
