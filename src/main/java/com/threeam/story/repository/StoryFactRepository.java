package com.threeam.story.repository;

import com.threeam.story.entity.StoryFact;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoryFactRepository extends JpaRepository<StoryFact, Long> {

    // 기록 순서(오래된 것부터)로 — 프롬프트에 시간순으로 싣는다.
    List<StoryFact> findByStoryIdOrderByIdAsc(Long storyId);

    // 재진단 가드용: 마지막 진단 이후 원장에 '새 근거'가 생겼는지.
    // 그 진단이 스스로 만든 사실(sourceAssessmentId 일치)은 새 근거가 아니다 —
    // 같은 트랜잭션에서 진단 직후에 적재돼 createdAt만으로는 구분이 안 되기 때문에 출처로 거른다.
    @Query("""
            select count(f) > 0 from StoryFact f
            where f.storyId = :storyId
              and f.createdAt >= :since
              and (f.sourceAssessmentId is null or f.sourceAssessmentId <> :lastAssessmentId)
            """)
    boolean existsNewFactSince(@Param("storyId") Long storyId,
                               @Param("since") LocalDateTime since,
                               @Param("lastAssessmentId") Long lastAssessmentId);
}
