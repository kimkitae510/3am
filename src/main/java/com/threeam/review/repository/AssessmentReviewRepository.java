package com.threeam.review.repository;

import com.threeam.review.entity.AssessmentReview;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentReviewRepository extends JpaRepository<AssessmentReview, Long> {

    Optional<AssessmentReview> findByAssessmentId(Long assessmentId);

    // 보상 지급 여부 판별 — 평가 이력이 하나라도 있으면 이미 받은 유저다.
    boolean existsByUserId(Long userId);
}
