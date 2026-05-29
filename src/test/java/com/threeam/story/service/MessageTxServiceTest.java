package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.llm.LlmRole;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
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
class MessageTxServiceTest {

    // 실문구는 로컬 설정(persona.yml 등)으로 주입되므로 테스트는 자리표시자를 채운 실객체를 쓴다.
    // 점검 기본값은 빈 문자열이고 비면 주입을 건너뛰므로, 프롬프트 '구조'를 검증하려면 여기서 채워야 한다.
    @Spy
    private ChatPersonaProperties personaProperties = personaProperties();

    private static final String FINAL_CHECK = "출력 직전 점검 자리표시자";
    private static final String QUESTION_REMINDER = "질문 원칙 자리표시자";

    private static ChatPersonaProperties personaProperties() {
        ChatPersonaProperties properties = new ChatPersonaProperties();
        properties.setFinalCheck(FINAL_CHECK);
        properties.setQuestionReminder(QUESTION_REMINDER);
        return properties;
    }

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @InjectMocks
    private MessageTxService messageTxService;

    @Test
    @DisplayName("재시도 준비 - 폴백을 지우고 같은 유저 메시지로 프롬프트를 다시 만든다(새로 저장하지 않는다)")
    void prepareRetry_deletesFallbackAndReusesUserMessage() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        Message userMessage = message(MessageRole.USER, "오늘 너무 힘들어");
        ReflectionTestUtils.setField(userMessage, "id", 7L);
        Message fallback = Message.fallback(story);
        ReflectionTestUtils.setField(fallback, "id", 8L);
        // 최신순 조회라 폴백이 먼저, 그 앞이 유저 메시지다
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(fallback, userMessage), PageRequest.of(0, 2), false));

        MessageTxService.PreparedRetry prepared = messageTxService.prepareRetry(1L, 10L);

        assertThat(prepared.pollAfterId()).isEqualTo(7L);
        assertThat(prepared.userContent()).isEqualTo("오늘 너무 힘들어");
        verify(messageRepository).delete(fallback);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("재시도 준비 - 마지막이 폴백이 아니면 거부한다(연타, 그새 답이 붙은 경우)")
    void prepareRetry_rejectedWhenNothingFailed() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .willReturn(Optional.of(story(10L)));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(
                        List.of(message(MessageRole.ASSISTANT, "들었어"),
                                message(MessageRole.USER, "오늘 너무 힘들어")),
                        PageRequest.of(0, 2), false));

        assertThatThrownBy(() -> messageTxService.prepareRetry(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_RETRY_NOT_APPLICABLE);

        verify(messageRepository, never()).delete(any(Message.class));
    }

    // 확률과 유형을 실어주면 채팅이 상담자가 아니라 진단 해설자가 됐다 — 유형 이름이 대화로
    // 새고, 새 사실이 나와도 낡은 판정에 묶였다(실측). 두 판정의 어긋남은 이제 답변 꼬리표로
    // 기록해 실측으로 잡는다.
    @Test
    @DisplayName("프롬프트 조립 - 진단 결과는 어떤 턴에도 싣지 않는다")
    void buildPrompt_neverCarriesAssessment() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "왜 이 진단이야? 확률이 궁금해")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = messageTxService
                .appendUserMessageAndBuildPrompt(1L, 10L, "왜 이 진단이야? 확률이 궁금해").prompt();

        // 진단을 정면으로 물은 턴에도 확률, 유형, 요인이 프롬프트에 없어야 한다.
        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .noneMatch(c -> c.contains("재회 진단 결과 데이터")
                        || c.contains("소진형")
                        || c.contains("재회 가능성: "));
    }

    @Test
    @DisplayName("프롬프트 조립 - 질문 원칙 리마인더는 첫 답변 턴에만 싣는다")
    void buildPrompt_questionReminderOnlyOnFirstAnswer() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        // 유저 메시지만 있는 대화 = 첫 답변 턴
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "사연이야")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> first = messageTxService
                .appendUserMessageAndBuildPrompt(1L, 10L, "사연이야").prompt();

        assertThat(first).extracting(ChatMessage::content).contains(QUESTION_REMINDER);

        // 어시스턴트 답이 이미 있는 대화 = 이후 턴. 매 턴 실으면 "질문은 꼭 해라" 압박이
        // 재점화돼 물을 게 없는 턴에도 질문을 짜낸다(실측) — 이후 턴 규칙은 본문이 맡는다.
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        message(MessageRole.USER, "그리고 이것도 있어"),
                        message(MessageRole.ASSISTANT, "지난 답변"),
                        message(MessageRole.USER, "사연이야")), PageRequest.of(0, 20), false));

        List<ChatMessage> later = messageTxService
                .appendUserMessageAndBuildPrompt(1L, 10L, "그리고 이것도 있어").prompt();

        assertThat(later).extracting(ChatMessage::content).doesNotContain(QUESTION_REMINDER);
        assertThat(later).extracting(ChatMessage::content).contains(FINAL_CHECK); // 점검은 매 턴 유지
    }

    @Test
    @DisplayName("프롬프트 조립 - 출력 직전 점검은 대화보다 뒤, 프롬프트의 맨 끝에 붙는다")
    void buildPrompt_finalCheckGoesLast() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "오늘 힘들어")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "오늘 힘들어").prompt();

        // 앞에 두면 리마인더와 같은 자리가 되어 묻힌다 — 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        ChatMessage last = prompt.get(prompt.size() - 1);
        assertThat(last.role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(last.content()).isEqualTo(FINAL_CHECK);
    }

    @Test
    @DisplayName("유저 메시지 저장 - 저장 후 시스템프롬프트 + 최근 맥락으로 프롬프트를 조립한다")
    void appendUser_success() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "오늘 힘들어")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "오늘 힘들어").prompt();

        assertThat(prompt.get(0).role()).isEqualTo(LlmRole.SYSTEM); // 맨 앞은 페르소나
        // 페르소나 + 유저 + 질문 원칙(첫 답변 턴) + 출력 직전 점검(맨 끝).
        assertThat(prompt).extracting(ChatMessage::role)
                .containsExactly(LlmRole.SYSTEM, LlmRole.USER, LlmRole.SYSTEM, LlmRole.SYSTEM);
        verify(messageRepository).save(any(Message.class)); // 유저 메시지 저장됨
    }

    @Test
    @DisplayName("프롬프트 조립 - 사실 원장이 있으면 기록일과 함께 시스템 메시지로 싣는다")
    void buildPrompt_includesFactLedger() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "안녕")),
                        PageRequest.of(0, 20), false));
        StoryFact fact = StoryFact.of(10L, "상대가 먼저 이별을 통보함", 1L);
        ReflectionTestUtils.setField(fact, "createdAt", java.time.LocalDateTime.of(2025, 11, 10, 3, 0));
        given(storyFactRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(List.of(fact));

        List<ChatMessage> prompt = messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "안녕").prompt();

        // 인덱스가 아니라 내용으로 찾는다 — 프롬프트 순서는 캐시 때문에 바뀔 수 있고,
        // 이 테스트가 검증할 것은 자리가 아니라 "원장이 실렸는가"다.
        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("기록된 사실") && c.contains("(11/10) 상대가 먼저 이별을 통보함"));
    }


    @Test
    @DisplayName("유저 메시지 저장 - 제목이 기본값이면 첫 메시지 내용으로 제목을 바꾼다")
    void appendUser_renamesDefaultTitle() {
        Story story = Story.builder().userId(1L).title(Story.DEFAULT_TITLE).build();
        ReflectionTestUtils.setField(story, "id", 10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "긴 얘기")), PageRequest.of(0, 20), false));

        messageTxService.appendUserMessageAndBuildPrompt(1L, 10L,
                "3년 만난 남자친구랑 2주 전에 헤어졌어. 걔가 먼저 헤어지자고 했어.");

        // 공백 정리 후 앞 20자 + 말줄임
        assertThat(story.getTitle()).isEqualTo("3년 만난 남자친구랑 2주 전에 헤어…");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 제목을 이미 지정한 사연은 건드리지 않는다")
    void appendUser_keepsCustomTitle() {
        Story story = story(10L);   // 제목 "사연"
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "안녕")), PageRequest.of(0, 20), false));

        messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "안녕");

        assertThat(story.getTitle()).isEqualTo("사연");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 없거나 남의 사연이면 STORY_NOT_FOUND, 저장하지 않는다")
    void appendUser_notFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("어시스턴트 응답 저장 - 응답을 저장하고 사연 활동시각을 갱신한다")
    void appendAssistant_success() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L, "괜찮아, 여기 있어");

        assertThat(response.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(response.getContent()).isEqualTo("괜찮아, 여기 있어");
        verify(messageRepository).save(any(Message.class));
    }

    private Story story(Long id) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "id", id);
        return story;
    }

    private Message message(MessageRole role, String content) {
        return Message.builder().role(role).content(content).build();
    }
}
