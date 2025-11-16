package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.StoryMemoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoryMemoryServiceTest {

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @InjectMocks
    private StoryMemoryService storyMemoryService;

    @Test
    @DisplayName("기존 요약이 있으면 통째로 교체한다")
    void upsert_replacesExisting() {
        StoryMemory memory = StoryMemory.builder().storyId(10L).summary("이별 직후 혼란").build();
        given(storyMemoryRepository.findByStoryId(10L)).willReturn(Optional.of(memory));

        storyMemoryService.upsert(10L, "조금씩 안정을 찾는 중");

        assertThat(memory.getSummary()).isEqualTo("조금씩 안정을 찾는 중");
        verify(storyMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("요약이 처음이면 새로 저장한다")
    void upsert_createsWhenAbsent() {
        given(storyMemoryRepository.findByStoryId(10L)).willReturn(Optional.empty());

        storyMemoryService.upsert(10L, "이별 직후 혼란");

        ArgumentCaptor<StoryMemory> captor = ArgumentCaptor.forClass(StoryMemory.class);
        verify(storyMemoryRepository).save(captor.capture());
        assertThat(captor.getValue().getSummary()).isEqualTo("이별 직후 혼란");
    }

    @Test
    @DisplayName("빈 요약(null/공백)은 '쓸 내용 없음'으로 보고 기존 요약을 유지한다")
    void upsert_ignoresBlank() {
        storyMemoryService.upsert(10L, null);
        storyMemoryService.upsert(10L, "  ");

        verifyNoInteractions(storyMemoryRepository);
    }
}
