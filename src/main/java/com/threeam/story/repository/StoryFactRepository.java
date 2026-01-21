package com.threeam.story.repository;

import com.threeam.story.entity.StoryFact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFactRepository extends JpaRepository<StoryFact, Long> {

    // 기록 순서(오래된 것부터)로 — 프롬프트에 시간순으로 싣는다.
    List<StoryFact> findByStoryIdOrderByIdAsc(Long storyId);

    // 프롬프트 주입용: 최근 N개만(최신 우선). 원장은 무제한으로 쌓이므로 매 호출에 통째로 실으면
    // 입력 토큰 비용이 대화 길이에 비례해 선형 증가한다. 호출부에서 시간순으로 뒤집어 쓴다.
    List<StoryFact> findByStoryIdOrderByIdDesc(Long storyId, Pageable pageable);

    // 재진단 가드용: 특정 문장(번복 확인 기록)의 가장 최근 시각.
    Optional<StoryFact> findFirstByStoryIdAndFactOrderByIdDesc(Long storyId, String fact);

}
