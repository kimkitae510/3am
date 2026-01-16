package com.threeam.story.repository;

import com.threeam.story.entity.StoryFact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFactRepository extends JpaRepository<StoryFact, Long> {

    // 기록 순서(오래된 것부터)로 — 프롬프트에 시간순으로 싣는다.
    List<StoryFact> findByStoryIdOrderByIdAsc(Long storyId);

}
