package com.threeam.story.repository;

import com.threeam.story.entity.Story;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRepository extends JpaRepository<Story, Long> {

    List<Story> findByUserIdOrderByUpdatedAtDesc(Long userId);

    // 소유권까지 한 번에 건다. 없거나 남의 것이면 빈 Optional → 404로 통일(존재 여부 노출 방지).
    Optional<Story> findByIdAndUserId(Long id, Long userId);
}
