package com.threeam.assessment.repository;

import com.threeam.assessment.entity.AssessmentReading;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentReadingRepository extends JpaRepository<AssessmentReading, Long> {

    List<AssessmentReading> findByAssessmentIdIn(Collection<Long> assessmentIds);
}
