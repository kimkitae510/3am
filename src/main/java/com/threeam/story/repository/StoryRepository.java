package com.threeam.story.repository;

import com.threeam.story.entity.Story;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRepository extends JpaRepository<Story, Long> {

    // 소프트 딜리트된 사연은 목록·소유권 조회에서 제외한다(deletedAt IS NULL만).
    List<Story> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    // 소유권까지 한 번에 건다. 없거나 남의 것이거나 삭제된 것이면 빈 Optional → 404로 통일(존재 여부 노출 방지).
    Optional<Story> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);
}
