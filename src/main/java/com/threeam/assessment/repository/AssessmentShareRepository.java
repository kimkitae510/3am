package com.threeam.assessment.repository;

import com.threeam.assessment.entity.AssessmentShare;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentShareRepository extends JpaRepository<AssessmentShare, Long> {

    Optional<AssessmentShare> findByToken(String token);

    // 같은 분석을 다시 공유하면 살아 있는 토큰을 재사용한다(링크가 여러 개면 어느 게 진짜인지
    // 흐려진다). 취소된 행은 무덤으로 남으므로 조회에서 뺀다 — 껐던 링크가 재공유로 되살아나면
    // 유저가 끊어낸 사람들에게 다시 열린다.
    Optional<AssessmentShare> findFirstByAssessmentIdAndRevokedAtIsNull(Long assessmentId);

    // 취소는 이 사연의 살아 있는 링크를 전부 끈다. 재분석마다 새 링크가 생기는 구조라,
    // 최신 것만 끄면 옛 분석의 링크가 조용히 열린 채 남는다.
    List<AssessmentShare> findByStoryIdAndRevokedAtIsNull(Long storyId);
}
