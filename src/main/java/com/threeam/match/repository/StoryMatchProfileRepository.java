package com.threeam.match.repository;

import com.threeam.match.entity.StoryMatchProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryMatchProfileRepository extends JpaRepository<StoryMatchProfile, Long> {

    // 스냅샷이 쌓이므로 매칭은 언제나 최신 한 장만 본다.
    Optional<StoryMatchProfile> findFirstByStoryIdOrderByIdDesc(Long storyId);
}
