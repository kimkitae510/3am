package com.threeam.assessment.repository;

import com.threeam.assessment.entity.AssessmentShare;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentShareRepository extends JpaRepository<AssessmentShare, Long> {

    Optional<AssessmentShare> findByToken(String token);

    // 같은 분석을 다시 공유하면 기존 토큰을 재사용한다(링크가 여러 개면 어느 게 진짜인지 흐려진다).
    Optional<AssessmentShare> findByAssessmentId(Long assessmentId);
}
