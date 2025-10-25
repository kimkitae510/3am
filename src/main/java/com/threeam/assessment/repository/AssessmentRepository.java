package com.threeam.assessment.repository;

import com.threeam.assessment.entity.Assessment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    List<Assessment> findByStoryIdOrderByCreatedAtDesc(Long storyId);
}
