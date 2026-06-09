package com.threeam.review.repository;

import com.threeam.review.entity.AssessmentReview;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentReviewRepository extends JpaRepository<AssessmentReview, Long> {

    Optional<AssessmentReview> findByAssessmentId(Long assessmentId);
}
