package com.threeam.story.repository;

import com.threeam.story.entity.Story;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoryRepository extends JpaRepository<Story, Long> {

    // 소프트 딜리트된 사연은 목록, 소유권 조회에서 제외한다(deletedAt IS NULL만).
    List<Story> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    // 소유권까지 한 번에 건다. 없거나 남의 것이거나 삭제된 것이면 빈 Optional → 404로 통일(존재 여부 노출 방지).
    Optional<Story> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // INSUFFICIENT 재시도 가드 표시. ts=null이면 해제(성공 진단 시). 벌크 UPDATE라 엔티티 로드가 불필요하다.
    @Modifying(clearAutomatically = true)
    @Query("update Story s set s.lastInsufficientAt = :ts where s.id = :id")
    void updateLastInsufficientAt(@Param("id") Long id, @Param("ts") LocalDateTime ts);
}
