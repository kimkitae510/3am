package com.threeam.review.repository;

import com.threeam.review.entity.AssessmentReview;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentReviewRepository extends JpaRepository<AssessmentReview, Long> {

    Optional<AssessmentReview> findByAssessmentId(Long assessmentId);

    // 보상 지급 여부 판별 — 보상 조건은 "후기까지 완성"이라, 후기 붙은 평가가 하나라도
    // 있으면 이미 받은 유저다.
    boolean existsByUserIdAndCommentIsNotNull(Long userId);
}
