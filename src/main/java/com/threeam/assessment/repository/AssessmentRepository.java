package com.threeam.assessment.repository;

import com.threeam.assessment.entity.Assessment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    List<Assessment> findByStoryIdOrderByCreatedAtDesc(Long storyId);

    // 채팅에서 "왜 이 진단이야?" 후속 질문에 답할 수 있게, 최신 진단 1건을 프롬프트에 싣는다.
    Optional<Assessment> findFirstByStoryIdOrderByCreatedAtDesc(Long storyId);
}
