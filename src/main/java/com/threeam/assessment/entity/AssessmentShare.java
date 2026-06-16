package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 분석 결과 공유 토큰. 분석 1건에 살아 있는 링크는 1개(스냅샷) — 재분석해도 이미 보낸
// 링크의 내용은 그대로다. 토큰이 곧 열쇠라(무인증 공개 조회) 순차 id가 아니라 추측
// 불가능한 랜덤 문자열을 쓴다.
// 취소는 행을 지우지 않고 시각만 찍는다. 지워버리면 같은 토큰이 언젠가 다시 나올 여지가
// 생기고(확률은 낮지만 죽인 링크가 되살아나는 건 사고다), 언제 껐는지도 남지 않는다.
@Entity
@Table(name = "assessment_shares")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assessmentId;

    // 방 삭제 시 링크를 죽이는 판별에 쓴다(공유는 방의 파생물이다).
    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64, unique = true)
    private String token;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // null이면 살아 있는 링크. 찍히면 공개 조회가 거절된다.
    @Column
    private LocalDateTime revokedAt;

    private AssessmentShare(Long assessmentId, Long storyId, Long userId, String token) {
        this.assessmentId = assessmentId;
        this.storyId = storyId;
        this.userId = userId;
        this.token = token;
    }

    public static AssessmentShare of(Long assessmentId, Long storyId, Long userId, String token) {
        return new AssessmentShare(assessmentId, storyId, userId, token);
    }

    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
