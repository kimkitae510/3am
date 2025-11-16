package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.LlmClient;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

@ExtendWith(MockitoExtension.class)
class StoryFactExtractorTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private StoryMemoryService storyMemoryService;

    private StoryFactExtractor extractor() {
        return new StoryFactExtractor(llmClient, new ObjectMapper(), messageRepository,
                storyFactRepository, storyMemoryRepository, storyFactService, storyMemoryService);
    }

    private void givenEmptyContext() {
        given(storyFactRepository.findByStoryIdOrderByIdAsc(10L)).willReturn(List.of());
        given(storyMemoryRepository.findByStoryId(10L)).willReturn(Optional.empty());
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
    }

    @Test
    @DisplayName("추출 - newFacts는 출처 없이(null) 원장으로, summary는 기억으로 넘긴다")
    void extract_appendsFactsAndSummary() {
        givenEmptyContext();
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(
                "{\"newFacts\": [\"일주일 전 상대에게서 연락 옴\"], \"summary\": \"연락을 받고 마음이 흔들리는 중\"}"));

        extractor().extractAsync(10L);

        verify(storyFactService).appendFacts(eq(10L), isNull(),
                eq(List.of("일주일 전 상대에게서 연락 옴")));
        verify(storyMemoryService).upsert(10L, "연락을 받고 마음이 흔들리는 중");
    }

    @Test
    @DisplayName("추출 - summary가 없으면 빈 문자열로 위임한다(기억 서비스가 무시해 기존 요약 유지)")
    void extract_blankSummaryKeepsMemory() {
        givenEmptyContext();
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(
                "{\"newFacts\": []}"));

        extractor().extractAsync(10L);

        verify(storyMemoryService).upsert(10L, "");
    }

    @Test
    @DisplayName("추출 - LLM 실패는 밖으로 던지지 않고 원장도 건드리지 않는다(채팅 흐름 보호)")
    void extract_swallowsLlmFailure() {
        givenEmptyContext();
        given(llmClient.generateJson(anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));

        assertThatCode(() -> extractor().extractAsync(10L)).doesNotThrowAnyException();

        verify(storyFactService, never()).appendFacts(anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("추출 - 깨진 JSON도 밖으로 던지지 않고 원장을 건드리지 않는다")
    void extract_swallowsMalformedJson() {
        givenEmptyContext();
        given(llmClient.generateJson(anyList()))
                .willReturn(CompletableFuture.completedFuture("이건 JSON이 아니야"));

        assertThatCode(() -> extractor().extractAsync(10L)).doesNotThrowAnyException();

        verify(storyFactService, never()).appendFacts(anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("추출 - 프롬프트 조립 단계의 실패(DB 등)도 밖으로 던지지 않는다")
    void extract_swallowsPrepareFailure() {
        given(storyFactRepository.findByStoryIdOrderByIdAsc(10L))
                .willThrow(new RuntimeException("DB down"));

        assertThatCode(() -> extractor().extractAsync(10L)).doesNotThrowAnyException();

        verify(llmClient, never()).generateJson(anyList());
    }
}
