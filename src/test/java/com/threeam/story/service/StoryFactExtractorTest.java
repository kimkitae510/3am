package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.FactExtractionProperties;
import com.threeam.llm.LlmClient;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.ArrayList;
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

    private static final long STORY_ID = 10L;

    // StoryFactExtractor.EXTRACT_THRESHOLD와 같은 값. 이 아래면 LLM을 부르지 않는다.
    private static final int THRESHOLD = 20;

    @Mock
    private LlmClient llmClient;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private StoryMemoryService storyMemoryService;

    private StoryFactExtractor extractor() {
        return new StoryFactExtractor(llmClient, new ObjectMapper(), new FactExtractionProperties(),
                messageRepository, storyRepository, storyFactRepository, storyMemoryRepository,
                storyFactService, storyMemoryService,
                // 콜백을 인라인 실행해 비동기 대기 없이 검증한다(운영에선 전용 풀).
                Runnable::run);
    }

    // id가 1..count인 유저/어시스턴트 번갈이 메시지.
    private List<Message> messages(int count) {
        List<Message> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Message message = mock(Message.class);
            lenient().when(message.getId()).thenReturn((long) i);
            lenient().when(message.getRole())
                    .thenReturn(i % 2 == 1 ? MessageRole.USER : MessageRole.ASSISTANT);
            lenient().when(message.getContent()).thenReturn("m" + i);
            list.add(message);
        }
        return list;
    }

    // 워터마크 없는 사연에 pending개가 밀려 있는 상태.
    private void givenPending(int pending) {
        Story story = mock(Story.class, RETURNS_DEEP_STUBS);
        lenient().when(story.getLastExtractedMessageId()).thenReturn(null);
        given(storyRepository.findById(STORY_ID)).willReturn(Optional.of(story));
        given(messageRepository.countByStoryIdAndIdGreaterThan(STORY_ID, 0L)).willReturn((long) pending);
    }

    // 임계를 넘겨 실제로 배치가 조회되는 경로까지 열어준다.
    private void givenExtractable(int pending) {
        givenPending(pending);
        // 메시지 mock 생성을 스터빙 표현식 밖에서 끝낸다 — 안에서 만들면 스터빙이 중첩돼 깨진다.
        SliceImpl<Message> batch = new SliceImpl<>(messages(pending), PageRequest.of(0, 40), false);
        given(messageRepository.findByStoryIdAndIdGreaterThanOrderByIdAsc(
                eq(STORY_ID), eq(0L), any(Pageable.class)))
                .willReturn(batch);
        lenient().when(storyFactRepository.findByStoryIdOrderByIdDesc(eq(STORY_ID), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(storyMemoryRepository.findByStoryId(STORY_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("게이팅 - 미추출 메시지가 임계 미만이면 LLM을 부르지 않는다")
    void skip_belowThreshold() {
        givenPending(THRESHOLD - 1);

        extractor().extractAsync(STORY_ID);

        verify(llmClient, never()).generateJson(anyList());
        verify(storyFactService, never()).markExtractedUpTo(anyLong(), anyLong());
    }

    @Test
    @DisplayName("게이팅 - 임계에 닿으면 추출하고 워터마크를 배치 마지막 id로 전진시킨다")
    void extract_advancesWatermark() {
        givenExtractable(THRESHOLD);
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(
                "{\"newFacts\": [\"일주일 전 상대에게서 연락 옴\"], \"summary\": \"연락을 받고 흔들리는 중\"}"));

        extractor().extractAsync(STORY_ID);

        verify(storyFactService).appendFacts(eq(STORY_ID), isNull(),
                eq(List.of("일주일 전 상대에게서 연락 옴")));
        verify(storyMemoryService).upsert(STORY_ID, "연락을 받고 흔들리는 중");
        verify(storyFactService).markExtractedUpTo(STORY_ID, (long) THRESHOLD);
    }

    @Test
    @DisplayName("게이팅 - 실패하면 워터마크를 전진시키지 않는다(다음 회차가 같은 구간을 다시 집는다)")
    void failure_keepsWatermark() {
        givenExtractable(THRESHOLD);
        given(llmClient.generateJson(anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));

        assertThatCode(() -> extractor().extractAsync(STORY_ID)).doesNotThrowAnyException();

        verify(storyFactService, never()).appendFacts(anyLong(), any(), anyList());
        verify(storyFactService, never()).markExtractedUpTo(anyLong(), anyLong());
    }

    @Test
    @DisplayName("추출 - summary가 없으면 빈 문자열로 위임한다(기억 서비스가 무시해 기존 요약 유지)")
    void extract_blankSummaryKeepsMemory() {
        givenExtractable(THRESHOLD);
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(
                "{\"newFacts\": []}"));

        extractor().extractAsync(STORY_ID);

        verify(storyMemoryService).upsert(STORY_ID, "");
    }

    @Test
    @DisplayName("추출 - 깨진 JSON도 밖으로 던지지 않고 원장과 워터마크를 건드리지 않는다")
    void extract_swallowsMalformedJson() {
        givenExtractable(THRESHOLD);
        given(llmClient.generateJson(anyList()))
                .willReturn(CompletableFuture.completedFuture("이건 JSON이 아니야"));

        assertThatCode(() -> extractor().extractAsync(STORY_ID)).doesNotThrowAnyException();

        verify(storyFactService, never()).appendFacts(anyLong(), any(), anyList());
        verify(storyFactService, never()).markExtractedUpTo(anyLong(), anyLong());
    }

    @Test
    @DisplayName("추출 - 프롬프트 조립 단계의 실패(DB 등)도 밖으로 던지지 않는다")
    void extract_swallowsPrepareFailure() {
        given(storyRepository.findById(STORY_ID)).willThrow(new RuntimeException("DB down"));

        assertThatCode(() -> extractor().extractAsync(STORY_ID)).doesNotThrowAnyException();

        verify(llmClient, never()).generateJson(anyList());
    }
}
