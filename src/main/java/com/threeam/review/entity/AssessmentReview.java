package com.threeam.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 진단 하나에 대한 유저의 정확도 평가. "재회가 될지"는 평가 시점에 검증할 수 없으므로
// 묻는 것은 예측이 아니라 "진단이 내 상황을 얼마나 제대로 짚었는가"다.
// 진단당 1행 유니크 — 중복 평가와 보상 이중 지급을 DB 수준에서 함께 막는다.
@Entity
@Table(name = "assessment_reviews", uniqueConstraints =
        @UniqueConstraint(name = "uk_review_assessment", columnNames = {"assessment_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    // 1(전혀 달라요) ~ 5(소름 돋게 맞아요)
    @Column(nullable = false)
    private int score;

    // 점수 뒤에 이어서 남기는 자유 텍스트. 높은 점수면 후기(전시 후보), 낮은 점수면
    // 오판 사유(골든셋 재료)로 쓰인다 — 어느 쪽인지는 score가 말해준다.
    @Column(length = 1000)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AssessmentReview(Long userId, Long assessmentId, int score) {
        this.userId = userId;
        this.assessmentId = assessmentId;
        this.score = score;
    }

    public void updateScore(int score) {
        this.score = score;
    }

    public void updateComment(String comment) {
        this.comment = comment;
    }
}
