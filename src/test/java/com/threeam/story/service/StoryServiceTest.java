package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.chip.ChipMatcher;
import com.threeam.chip.ChipStore;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.global.exception.custom.RetryAfterException;
import com.threeam.llm.LlmClient;
import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageRetryResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.usage.ChatRetryGuard;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
    private ReplyLinter replyLinter;

    @Mock
    private LlmClient llmClient;

    @Mock
    private UsageLimiter usageLimiter;

    // 기본 스텁은 0(차단 아님)이라 나머지 테스트는 이 가드를 신경 쓰지 않는다.
    @Mock
    private ChatRetryGuard chatRetryGuard;

    // 콜백 전용 풀 자리. 테스트에선 인라인 실행이라 비동기 대기 없이 검증한다(운영에선 LlmCallbackConfig의 풀).
    @Spy
    private Executor llmCallbackExecutor = new InlineExecutor();

    static class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    @Mock
    private ChipStore chipStore;

    // 자유입력 판별. 기본 스텁이 null이라 명시적으로 완료된 future를 준다 —
    // 이게 없으면 상담 호출이 매달린 체인이 시작되지 않는다.
    @Mock
    private ChipMatcher chipMatcher;

    @InjectMocks
    private StoryService storyService;

    // 판별은 자유입력 턴에만 도는 부가 단계라, 여기 테스트들은 "이미 끝난 것"으로 두고 지나간다.
    // 목 기본값(null)을 그대로 두면 상담 호출이 매달린 체인 자체가 시작되지 않는다.
    @BeforeEach
    void chipMatchDone() {
        lenient().when(chipMatcher.matchAsync(anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

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
    @DisplayName("사연 목록 - 마지막 메시지를 한 줄 미리보기로 붙인다(개행 평탄화, 60자 절단, 대화 없으면 null)")
    void getStories_withLastMessagePreview() {
        Story first = story(1L, "첫 사연");
        ReflectionTestUtils.setField(first, "id", 10L);
        Story second = story(1L, "둘째 사연");
        ReflectionTestUtils.setField(second, "id", 20L);
        given(storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(1L))
                .willReturn(List.of(first, second));
        given(messageRepository.findLatestPerStory(List.of(10L, 20L)))
                .willReturn(List.of(Message.assistant(first, "첫 줄\n둘째 줄 " + "가".repeat(80))));

        List<StoryResponse> responses = storyService.getStories(1L);

        assertThat(responses.get(0).getLastMessage()).startsWith("첫 줄 둘째 줄").hasSize(60);
        assertThat(responses.get(1).getLastMessage()).isNull(); // 아직 대화가 없는 방
    }

    @Test
    @DisplayName("메시지 전송 - 유저 메시지를 즉시 반환하고, 어시스턴트 답은 백그라운드로 저장한다")
    void sendMessage_success() {
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, "오늘 너무 힘들어"));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "오늘 너무 힘들어", null))
                .willReturn(new MessageTxService.PreparedSend(userMessage, 1L));
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
        verify(usageLimiter).record(UsageKind.CHAT, 1L, 1);
        // 답이 저장된 턴만 사실 추출이 돈다(별도 호출, 쿼터 미차감)
        verify(factExtractor).extractAsync(10L);
        // 답 저장까지 끝났으니 in-flight 잠금도 해제된다
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
    }

    @Test
    @DisplayName("메시지 전송 - 길이와 무관하게 한 턴은 1회다(비용을 정하는 건 호출 수라서)")
    void sendMessage_lengthDoesNotChangeUnits() {
        String longContent = "가".repeat(2800);
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, longContent));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, longContent, null))
                .willReturn(new MessageTxService.PreparedSend(userMessage, 1L));
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("들었어."));
        given(messageTxService.appendAssistantReply(10L, "들었어."))
                .willReturn(MessageResponse.from(message(2L, MessageRole.ASSISTANT, "들었어.")));

        storyService.sendMessage(1L, 10L, sendRequest(longContent));

        verify(usageLimiter).check(UsageKind.CHAT, 1L, 1);
        verify(usageLimiter).record(UsageKind.CHAT, 1L, 1);
    }

    @Test
    @DisplayName("메시지 전송 - 없거나 남의 사연이면 STORY_NOT_FOUND, LLM 호출도 쿼터 기록도 없다")
    void sendMessage_notFound() {
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi", null))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(llmClient, never()).generate(anyList());
        // 후차감이라 성공 전에 실패하면 기록할 것이 없다. 잠금만 해제.
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
    }

    @Test
    @DisplayName("메시지 전송 - 이 사연의 답변이 생성 중이면 접수를 거부한다(연타 차단)")
    void sendMessage_inFlightRejected() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.CHAT, 1L);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        // 접수 자체가 거부됐으니 한도 검사도, 메시지 저장도, LLM 호출도 없다
        verify(usageLimiter, never()).check(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any(), any());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - 일일 한도를 넘으면 QUOTA_EXCEEDED, 잠금을 해제하고 LLM을 호출하지 않는다")
    void sendMessage_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).check(UsageKind.CHAT, 1L, 1);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any(), any());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - LLM 실패로 폴백을 저장한 경우에도 잠금은 해제된다")
    void sendMessage_llmFailureReleasesLock() {
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, "hi"));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi", null))
                .willReturn(new MessageTxService.PreparedSend(userMessage, 1L));
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));

        storyService.sendMessage(1L, 10L, sendRequest("hi"));

        // 실패 시 폴백 메시지가 저장되고(폴링 정상 종료), 잠금도 풀린다
        verify(messageTxService).appendAssistantReply(eq(10L), any(String.class));
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
        // 성공 시만 차감: LLM 장애로 폴백이 나간 턴은 유저 쿼터를 쓰지 않는다
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        // 답이 없는 턴은 추출할 것도 없다
        verify(factExtractor, never()).extractAsync(any());
        // 미차감이라 무한 무료 호출이 가능한 자리 — 연속 실패로 세어 둔다
        verify(chatRetryGuard).markFailed(1L);
    }

    @Test
    @DisplayName("메시지 전송 - 답이 저장되면 연속 실패 카운트를 지운다")
    void sendMessage_successClearsFailStreak() {
        MessageResponse userMessage = MessageResponse.from(message(1L, MessageRole.USER, "hi"));
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi", null))
                .willReturn(new MessageTxService.PreparedSend(userMessage, 1L));
        given(llmClient.generate(anyList())).willReturn(CompletableFuture.completedFuture("들었어"));
        given(messageTxService.appendAssistantReply(10L, "들었어"))
                .willReturn(MessageResponse.from(message(2L, MessageRole.ASSISTANT, "들었어")));

        storyService.sendMessage(1L, 10L, sendRequest("hi"));

        verify(chatRetryGuard).clear(1L);
        verify(chatRetryGuard, never()).markFailed(any());
    }

    @Test
    @DisplayName("메시지 전송 - 연속 실패 쿨다운 중이면 남은 초와 함께 거부하고 LLM을 부르지 않는다")
    void sendMessage_failCooldownRejected() {
        given(chatRetryGuard.blockedSeconds(1L)).willReturn(42);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(RetryAfterException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_RETRY_COOLDOWN)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 42);

        // 유저 메시지도 저장하지 않는다 — 답이 붙지 않을 말풍선만 남으면 폴링이 헛돈다
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any(), any());
        verify(llmClient, never()).generate(anyList());
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
    }

    @Test
    @DisplayName("메시지 전송 - 잔여가 없으면 쿨다운 검사까지 가지 않는다(할 일이 있는 안내가 우선)")
    void sendMessage_quotaBeforeCooldown() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).check(UsageKind.CHAT, 1L, 1);

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(chatRetryGuard, never()).blockedSeconds(any());
    }

    @Test
    @DisplayName("답변 재시도 - 유저 메시지를 새로 저장하지 않고 답만 다시 만든다")
    void retryLastReply_success() {
        given(messageTxService.prepareRetry(1L, 10L))
                .willReturn(new MessageTxService.PreparedRetry(7L, "오늘 너무 힘들어", List.of()));
        given(llmClient.generate(anyList())).willReturn(CompletableFuture.completedFuture("들었어"));
        given(messageTxService.appendAssistantReply(10L, "들었어"))
                .willReturn(MessageResponse.from(message(9L, MessageRole.ASSISTANT, "들었어")));

        MessageRetryResponse response = storyService.retryLastReply(1L, 10L);

        // 폴백을 지웠으니 클라가 들고 있던 id는 없는 행이다 — 폴링 기준을 새로 준다
        assertThat(response.getPollAfterId()).isEqualTo(7L);
        // 같은 말을 다시 저장하지 않는다(중복 말풍선, 중복 사실 추출 방지)
        verify(messageTxService, never()).appendUserMessageAndBuildPrompt(any(), any(), any(), any());
        verify(messageTxService).appendAssistantReply(10L, "들었어");
        // 재시도라고 회수를 더 받지도, 깎아주지도 않는다
        verify(usageLimiter).record(UsageKind.CHAT, 1L, 1);
        verify(chatRetryGuard).clear(1L);
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
    }

    @Test
    @DisplayName("답변 재시도 - 잔여가 없으면 폴백을 지우지 않는다(재시도 버튼이 사라지면 안 된다)")
    void retryLastReply_quotaExceededKeepsFallback() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).check(UsageKind.CHAT, 1L, 1);

        assertThatThrownBy(() -> storyService.retryLastReply(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(messageTxService, never()).prepareRetry(any(), any());
        verify(llmClient, never()).generate(anyList());
        verify(usageLimiter).releaseInFlight(UsageKind.CHAT, 1L);
    }

    @Test
    @DisplayName("답변 재시도 - 연속 실패 쿨다운 중이면 거부한다(가드를 우회하는 문이 되면 안 된다)")
    void retryLastReply_blockedByCooldown() {
        given(chatRetryGuard.blockedSeconds(1L)).willReturn(30);

        assertThatThrownBy(() -> storyService.retryLastReply(1L, 10L))
                .isInstanceOf(RetryAfterException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 30);

        verify(messageTxService, never()).prepareRetry(any(), any());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("폴링 - 생성이 돌고 있지 않은데 답이 없으면 끊긴 턴이다. 폴백으로 닫아 폴링을 끝낸다")
    void getMessagesSince_healsDanglingTurn() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .willReturn(Optional.of(story(1L, "사연")));
        given(messageRepository.existsByStoryIdAndIdGreaterThan(10L, 5L)).willReturn(false);
        given(usageLimiter.isGenerating(UsageKind.CHAT, 1L)).willReturn(false);
        // 마지막이 유저 메시지 — 서버가 재시작돼 답도 폴백도 못 남긴 자리
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(5L, MessageRole.USER, "hi")),
                        PageRequest.of(0, 1), false));
        given(messageRepository.findByStoryIdAndIdGreaterThanOrderByIdAsc(10L, 5L))
                .willReturn(List.of(message(6L, MessageRole.ASSISTANT, Message.FALLBACK_CONTENT)));

        List<MessageResponse> fresh = storyService.getMessagesSince(1L, 10L, 5L);

        verify(messageTxService).appendAssistantReply(10L, Message.FALLBACK_CONTENT);
        // 폴백이 폴링에 잡혀야 화면의 "..."가 끝나고 재시도 버튼이 뜬다
        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).isFailed()).isTrue();
        // 우리 재시작이지 LLM이 반복 실패한 게 아니다 — 연속 실패로 세지 않는다
        verify(chatRetryGuard, never()).markFailed(any());
    }

    @Test
    @DisplayName("폴링 - 아직 생성 중이면 손대지 않는다(정상 대기를 실패로 닫으면 안 된다)")
    void getMessagesSince_keepsWaitingWhileGenerating() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .willReturn(Optional.of(story(1L, "사연")));
        given(messageRepository.existsByStoryIdAndIdGreaterThan(10L, 5L)).willReturn(false);
        given(usageLimiter.isGenerating(UsageKind.CHAT, 1L)).willReturn(true);
        given(messageRepository.findByStoryIdAndIdGreaterThanOrderByIdAsc(10L, 5L)).willReturn(List.of());

        assertThat(storyService.getMessagesSince(1L, 10L, 5L)).isEmpty();

        verify(messageTxService, never()).appendAssistantReply(any(), any());
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
    @DisplayName("사연 삭제 - 물리 삭제하지 않고 소프트 딜리트(시각 마킹)한다. 대화, 진단은 남긴다")
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
