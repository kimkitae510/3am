package com.threeam.story.repository;

import com.threeam.story.entity.StoryFact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFactRepository extends JpaRepository<StoryFact, Long> {

    // 기록 순서(오래된 것부터)로 — 프롬프트에 시간순으로 싣는다.
    List<StoryFact> findByStoryIdOrderByIdAsc(Long storyId);

    // 재진단 가드용: 특정 문장(번복 확인 기록)의 가장 최근 시각.
    Optional<StoryFact> findFirstByStoryIdAndFactOrderByIdDesc(Long storyId, String fact);

}
