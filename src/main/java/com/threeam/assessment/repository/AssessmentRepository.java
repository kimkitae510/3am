package com.threeam.assessment.repository;

import com.threeam.assessment.entity.Assessment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    List<Assessment> findByStoryIdOrderByCreatedAtDesc(Long storyId);

    // 채팅에서 "왜 이 분석이야?" 후속 질문에 답할 수 있게, 최신 분석 1건을 프롬프트에 싣는다.
    Optional<Assessment> findFirstByStoryIdOrderByCreatedAtDesc(Long storyId);

    // 판독의 변동내역 기준(base) — 이 판정보다 앞선, 확률이 있는 가장 최근 판정.
    // 잠금 판정(확률 null)은 비교 기준이 못 되므로 건너뛴다.
    Optional<Assessment> findFirstByStoryIdAndIdLessThanAndProbabilityIsNotNullOrderByIdDesc(
            Long storyId, Long id);
}
