package com.threeam.story.repository;

import com.threeam.story.entity.StoryIntake;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryIntakeRepository extends JpaRepository<StoryIntake, Long> {

    Optional<StoryIntake> findByStoryId(Long storyId);

    // 두 번째 사연부터 내 나이, 성별을 미리 채워주기 위한 조회. 유저 테이블에 프로필을 새로
    // 만들지 않고 직전 입력을 물려받는다 — 저장 위치를 늘리지 않고 반복 입력만 없앤다.
    List<StoryIntake> findByStoryIdInOrderByIdDesc(List<Long> storyIds);
}
