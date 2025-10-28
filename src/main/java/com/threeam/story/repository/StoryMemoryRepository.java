package com.threeam.story.repository;

import com.threeam.story.entity.StoryMemory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryMemoryRepository extends JpaRepository<StoryMemory, Long> {

    Optional<StoryMemory> findByStoryId(Long storyId);

    void deleteByStoryId(Long storyId);
}
