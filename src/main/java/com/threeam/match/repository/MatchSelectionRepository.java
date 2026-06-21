package com.threeam.match.repository;

import com.threeam.match.entity.MatchSelection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchSelectionRepository extends JpaRepository<MatchSelection, Long> {

    Optional<MatchSelection> findByAssessmentId(Long assessmentId);
}
