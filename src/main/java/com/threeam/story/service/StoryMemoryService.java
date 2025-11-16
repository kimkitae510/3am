package com.threeam.story.service;

import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.StoryMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 감정 흐름 요약(StoryMemory)의 유일한 쓰기 창구. 진단과 채팅 추출이 함께 쓴다.
// 원장(사실)과 달리 요약은 "현재 상태" 한 장이면 충분해서 쌓지 않고 통째로 교체한다.
@Service
@RequiredArgsConstructor
public class StoryMemoryService {

    private final StoryMemoryRepository storyMemoryRepository;

    // 빈 요약은 "새로 쓸 내용 없음"으로 보고 기존 요약을 유지한다.
    @Transactional
    public void upsert(Long storyId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        storyMemoryRepository.findByStoryId(storyId)
                .ifPresentOrElse(
                        memory -> memory.updateSummary(summary),
                        () -> storyMemoryRepository.save(
                                StoryMemory.builder().storyId(storyId).summary(summary).build()));
    }
}
