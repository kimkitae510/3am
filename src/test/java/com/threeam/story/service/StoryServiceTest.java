package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.LlmClient;
import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoryServiceTest {

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageTxService messageTxService;

    @Mock
    private StoryFactExtractor factExtractor;

    @Mock
    private LlmClient llmClient;

    @Mock
    private UsageLimiter usageLimiter;

    @InjectMocks
    private StoryService storyService;

    @Test
    @DisplayName("사연 생성 - 제목을 지정하면 그대로 저장한다")
    void create_withTitle() {
        given(storyRepository.save(any(Story.class))).willAnswer(inv -> inv.getArgument(0));

        StoryResponse response = storyService.create(1L, createRequest("힘든 밤"));

        assertThat(response.getTitle()).isEqualTo("힘든 밤");
        verify(storyRepository).save(any(Story.class));
    }

    @Test
    @DisplayName("사연 생성 - 제목이 비어 있으면 기본 제목을 붙인다")
    void create_defaultTitle() {
        given(storyRepository.save(any(Story.class))).willAnswer(inv -> inv.getArgument(0));

        StoryResponse response = storyService.create(1L, createRequest("  "));

        assertThat(response.getTitle()).isEqualTo("새 대화");
    }

    @Test
    @DisplayName("사연 목록 - 유저의 사연을 최근 활동순으로 반환한다")
    void getStories_success() {
        given(storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(1L))
                .willReturn(List.of(story(1L, "첫 사연"), story(1L, "둘째 사연")));

        List<StoryResponse> responses = storyService.getStories(1L);

        assertThat(responses).extracting(StoryResponse::getTitle)
                .containsExactly("첫 사연", "둘째 사연");
    }

    @Test
    @DisplayName("메시지 전송 - 유저 메시지를 즉시 반환하고, 어시스턴트 답은 백그라운드로 저장한다")
    void sendMessage_success() {
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, "오늘 너무 힘들어"));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "오늘 너무 힘들어"))
                .willReturn(new MessageTxService.PreparedSend(userMessage, List.of()));
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("괜찮아요, 여기 있어요."));
        given(messageTxService.appendAssistantReply(10L, "괜찮아요, 여기 있어요."))
                .willReturn(MessageResponse.from(message(2L, MessageRole.ASSISTANT, "괜찮아요, 여기 있어요.")));

        MessageResponse response = storyService.sendMessage(1L, 10L, sendRequest("오늘 너무 힘들어"));

        // 즉시 반환값은 '내 메시지'
        assertThat(response.getRole()).isEqualTo(MessageRole.USER);
        assertThat(response.getContent()).isEqualTo("오늘 너무 힘들어");
        verify(llmClient).generate(anyList());
        // completedFuture라 thenAccept가 동기 실행 → 어시스턴트 저장까지 이뤄진다
        verify(messageTxService).appendAssistantReply(10L, "괜찮아요, 여기 있어요.");
        // 후차감: 답 저장이 성공했으니 이 시점에 1회 기록된다
        verify(usageLimiter).recordDaily(UsageKind.CHAT, 1L);
        // 답이 저장된 턴만 사실 추출이 돈다(별도 호출, 쿼터 미차감)
        verify(factExtractor).extractAsync(10L);
        // 답 저장까지 끝났으니 in-flight 잠금도 해제된다
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 10L);
    }

    @Test
    @DisplayName("메시지 전송 - 없거나 남의 사연이면 STORY_NOT_FOUND, LLM 호출도 쿼터 기록도 없다")
    void sendMessage_notFound() {
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi"))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(llmClient, never()).generate(anyList());
        // 후차감이라 성공 전에 실패하면 기록할 것이 없다. 잠금만 해제.
        verify(usageLimiter, never()).recordDaily(any(), any());
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 10L);
    }

    @Test
    @DisplayName("메시지 전송 - 이 사연의 답변이 생성 중이면 접수를 거부한다(연타 차단)")
    void sendMessage_inFlightRejected() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.CHAT, 10L);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        // 접수 자체가 거부됐으니 한도 검사도, 메시지 저장도, LLM 호출도 없다
        verify(usageLimiter, never()).checkDaily(any(), any());
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - 일일 한도를 넘으면 QUOTA_EXCEEDED, 잠금을 해제하고 LLM을 호출하지 않는다")
    void sendMessage_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).checkDaily(UsageKind.CHAT, 1L);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 10L);
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - LLM 실패로 폴백을 저장한 경우에도 잠금은 해제된다")
    void sendMessage_llmFailureReleasesLock() {
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, "hi"));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi"))
                .willReturn(new MessageTxService.PreparedSend(userMessage, List.of()));
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));

        storyService.sendMessage(1L, 10L, sendRequest("hi"));

        // 실패 시 폴백 메시지가 저장되고(폴링 정상 종료), 잠금도 풀린다
        verify(messageTxService).appendAssistantReply(eq(10L), any(String.class));
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 10L);
        // 성공 시만 차감: LLM 장애로 폴백이 나간 턴은 유저 쿼터를 쓰지 않는다
        verify(usageLimiter, never()).recordDaily(any(), any());
        // 답이 없는 턴은 추출할 것도 없다
        verify(factExtractor, never()).extractAsync(any());
    }

    @Test
    @DisplayName("메시지 조회 - 커서 없이 최신 페이지를 과거→현재 순으로 반환한다")
    void getMessages_firstPage() {
        Story story = story(1L, "사연");
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        Message older = message(1L, MessageRole.USER, "안녕");
        Message newer = message(2L, MessageRole.ASSISTANT, "안녕하세요");
        // 조회는 id 역순(최신 먼저)으로 온다. hasNext=true(더 과거 있음)로 가정.
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(newer, older), PageRequest.of(0, 30), true));

        MessagePageResponse response = storyService.getMessages(1L, 10L, null, 30);

        assertThat(response.getMessages()).extracting(MessageResponse::getContent)
                .containsExactly("안녕", "안녕하세요"); // 과거→현재로 뒤집혀 나온다
        assertThat(response.getNextCursor()).isEqualTo(1L); // 이번 배치에서 가장 오래된 id
        assertThat(response.isHasNext()).isTrue();
    }

    @Test
    @DisplayName("메시지 조회 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void getMessages_notFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.getMessages(1L, 10L, null, 30))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("사연 삭제 - 물리 삭제하지 않고 소프트 딜리트(시각 마킹)한다. 대화·진단은 남긴다")
    void deleteStory_success() {
        Story story = story(1L, "사연");
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));

        storyService.deleteStory(1L, 10L);

        assertThat(story.isDeleted()).isTrue();
        verify(messageRepository, never()).deleteByStoryId(any());
        verify(storyRepository, never()).delete(any(Story.class));
    }

    @Test
    @DisplayName("사연 삭제 - 없거나 남의(이미 삭제된) 사연이면 STORY_NOT_FOUND")
    void deleteStory_notFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.deleteStory(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(storyRepository, never()).delete(any(Story.class));
    }

    private Story story(Long userId, String title) {
        return Story.builder().userId(userId).title(title).build();
    }

    private Message message(Long id, MessageRole role, String content) {
        Message message = Message.builder().role(role).content(content).build();
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private StoryCreateRequest createRequest(String title) {
        StoryCreateRequest request = new StoryCreateRequest();
        ReflectionTestUtils.setField(request, "title", title);
        return request;
    }

    private MessageSendRequest sendRequest(String content) {
        MessageSendRequest request = new MessageSendRequest();
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}
