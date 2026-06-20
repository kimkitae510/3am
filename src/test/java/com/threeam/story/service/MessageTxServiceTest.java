package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.chip.ChipDefinition;
import com.threeam.chip.DiagnosisContext;
import com.threeam.chip.ChipInteraction;
import com.threeam.chip.ChipStore;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.entity.WatchPoint;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.llm.LlmRole;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.ReunionDirection;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private static final String TURN1_CHECK = "첫 답변 점검 자리표시자";
    private static final String QUESTION = "질문 원칙 자리표시자";
    private static final String ANALYSIS = "분석 규칙 자리표시자";
    private static final String PSYCHOLOGY = "관계심리 어휘 자리표시자";

    private static ChatPersonaProperties personaProperties() {
        ChatPersonaProperties properties = new ChatPersonaProperties();
        properties.setFinalCheck(FINAL_CHECK);
        properties.setTurn1Check(TURN1_CHECK);
        properties.setQuestion(QUESTION);
        properties.setAnalysis(ANALYSIS);
        properties.setRelationshipPsychology(PSYCHOLOGY);
        properties.setAssessmentGuide("이 값과 어긋나게 말하지 마라");
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

    @Mock
    private StoryIntakeRepository storyIntakeRepository;

    // 기본 스텁이 find()에 null을 주므로 여기 테스트들은 전부 칩 없는 턴으로 돈다.
    @Mock
    private ChipStore chipStore;

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

    // 결과 라벨(유형, 요인 이름)을 실었더니 그 단어가 그대로 대화에 새어나왔고("소진형 상태거든"),
    // 확률만 실었더니 새 사실이 나와도 낡은 값에 묶였다(둘 다 실측). 재료로 싣되 내부 어휘는 뺀다.
    // 자유입력이라 평소엔 분석이 안 실리지만, 유저가 확률을 직접 물으면 그 턴만 전부 싣는다.
    @Test
    @DisplayName("프롬프트 조립 - 진단은 확률과 사실로만 실리고 내부 라벨은 안 실린다")
    void buildPrompt_carriesAssessmentAsMaterial() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "확률이 왜 이렇게 나왔어")),
                        PageRequest.of(0, 20), false));
        Assessment assessment = Assessment.builder()
                .storyId(10L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(19)
                .breakupType(BreakupType.BURNOUT)
                .factor(AssessmentFactor.of(FactorName.REPLACEMENT, FactorLevel.STRONG_UNFAVORABLE,
                        "상대에게 새 연인이 정착함", null, null))
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.NEUTRAL,
                        "근거 없음", null, null))
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "createdAt", java.time.LocalDateTime.now());
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(10L))
                .willReturn(Optional.of(assessment));

        List<ChatMessage> prompt = sendAndBuild(1L, 10L, "확률이 왜 이렇게 나왔어", null);

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                // 확률(강도 기준점)과 사실은 실린다
                .anyMatch(c -> c.contains("19%") && c.contains("상대에게 새 연인이 정착함")
                        && c.contains("크게 낮춤"))
                // 유형, 요인 이름 같은 내부 어휘와 중립 슬롯은 안 실린다
                .noneMatch(c -> c.contains("소진형") || c.contains("대체자")
                        || c.contains("상대신호") || c.contains("근거 없음"));
    }

    // 관계 심리는 RELATIONSHIP 정책이 열리는 자리(재회 후 관계 전망)에서만 실린다.
    @Test
    @DisplayName("프롬프트 조립 - 관계 심리 라벨은 실린다(유저에게 말하는 어휘 — 채팅이 딴 이름을 짓지 않게)")
    void buildPrompt_carriesRelationshipPsychologyLabels() {
        Story story = story(10L);
        stubChip("OUTLOOK_REPEAT", "RELATIONSHIP_OUTLOOK", DiagnosisContext.RELATIONSHIP);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        Message.user(story, "다시 만나면 같은 문제가 반복될까요?", "OUTLOOK_REPEAT")),
                        PageRequest.of(0, 20), false));
        Assessment assessment = Assessment.builder()
                .storyId(10L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(35)
                .breakupType(BreakupType.BURNOUT)
                .relationshipPsychology(new com.threeam.assessment.dto.RelationshipPsychology(
                        new com.threeam.assessment.dto.RelationshipPsychology.Attachment(
                                new com.threeam.assessment.dto.RelationshipPsychology.Style("불안형", "중간"),
                                new com.threeam.assessment.dto.RelationshipPsychology.Style("회피형", "높음"),
                                "설명은 프롬프트에 안 실린다"),
                        new com.threeam.assessment.dto.RelationshipPsychology.PatternItem(
                                "추구-회피", "높음", "설명은 프롬프트에 안 실린다"),
                        null))
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "createdAt", java.time.LocalDateTime.now());
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(10L))
                .willReturn(Optional.of(assessment));

        List<ChatMessage> prompt = sendAndBuild(
                1L, 10L, "다시 만나면 같은 문제가 반복될까요?", "OUTLOOK_REPEAT");

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                // 라벨은 실리고 설명 문장은 안 실린다(지난 서사 반복 방지)
                .anyMatch(c -> c.contains("추구-회피") && c.contains("불안형") && c.contains("회피형"))
                .noneMatch(c -> c.contains("설명은 프롬프트에 안 실린다"))
                // 재회 뒤 관계를 보는 자리라 확률은 안 실린다 — 숫자가 있으면 그 숫자를 지키는 답이 된다
                .noneMatch(c -> c.contains("35%"));
    }

    @Test
    @DisplayName("프롬프트 조립 - 회차가 맡지 않은 일의 규칙은 싣지 않는다")
    void buildPrompt_loadsOnlySectionsForThatAnswerNo() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        // 유저 메시지만 있는 대화 = 첫 답변 턴
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "사연이야")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> first = sendAndBuild(1L, 10L, "사연이야", null);

        // 1회차는 묻기만 한다 — 분석 규칙이 실리면 지시로 미뤄도 결국 분석한다(실측)
        assertThat(first).extracting(ChatMessage::content).doesNotContain(ANALYSIS);
        // 질문 선정 기준은 turn-1이 QUESTION 지침에 위임하므로 첫 턴에 같이 실린다
        assertThat(first).extracting(ChatMessage::content).contains(QUESTION);
        // 관계심리는 개념을 어떻게 다룰지의 어휘 규칙이라 첫 턴에도 실린다
        assertThat(first).extracting(ChatMessage::content).contains(PSYCHOLOGY);
        // 공용 점검은 분석이 끝난 답을 전제로 물어 첫 턴과 부딪힌다 — 첫 턴만 전용 판
        assertThat(first).extracting(ChatMessage::content).contains(TURN1_CHECK).doesNotContain(FINAL_CHECK);

        // 어시스턴트 답이 이미 있는 대화 = 이후 턴.
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        message(MessageRole.USER, "그리고 이것도 있어"),
                        message(MessageRole.ASSISTANT, "지난 답변"),
                        message(MessageRole.USER, "사연이야")), PageRequest.of(0, 20), false));

        List<ChatMessage> later = sendAndBuild(1L, 10L, "그리고 이것도 있어", null);

        assertThat(later).extracting(ChatMessage::content).doesNotContain(QUESTION);
        assertThat(later).extracting(ChatMessage::content).contains(ANALYSIS);
        assertThat(later).extracting(ChatMessage::content).contains(PSYCHOLOGY);
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

        List<ChatMessage> prompt = sendAndBuild(1L, 10L, "오늘 힘들어", null);

        // 앞에 두면 리마인더와 같은 자리가 되어 묻힌다 — 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        ChatMessage last = prompt.get(prompt.size() - 1);
        assertThat(last.role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(last.content()).isEqualTo(TURN1_CHECK); // 첫 답변 턴이라 전용 점검
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

        List<ChatMessage> prompt = sendAndBuild(1L, 10L, "오늘 힘들어", null);

        assertThat(prompt.get(0).role()).isEqualTo(LlmRole.SYSTEM); // 맨 앞은 페르소나
        // 페르소나 + 유저 + 관계심리, 질문 원칙(첫 답변 턴) + 출력 직전 점검(맨 끝).
        assertThat(prompt).extracting(ChatMessage::role)
                .containsExactly(LlmRole.SYSTEM, LlmRole.USER, LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.SYSTEM);
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

        List<ChatMessage> prompt = sendAndBuild(1L, 10L, "안녕", null);

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

        messageTxService.appendUserMessageAndBuildPrompt(1L, 10L,
                "3년 만난 남자친구랑 2주 전에 헤어졌어. 걔가 먼저 헤어지자고 했어.", null);

        // 공백 정리 후 앞 20자 + 말줄임
        assertThat(story.getTitle()).isEqualTo("3년 만난 남자친구랑 2주 전에 헤어…");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 제목을 이미 지정한 사연은 건드리지 않는다")
    void appendUser_keepsCustomTitle() {
        Story story = story(10L);   // 제목 "사연"
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "안녕", null);

        assertThat(story.getTitle()).isEqualTo("사연");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 없거나 남의 사연이면 STORY_NOT_FOUND, 저장하지 않는다")
    void appendUser_notFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi", null))
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

    @Test
    @DisplayName("어시스턴트 응답 저장 - 마크다운 기호는 저장 전에 걷어낸다(흘린 굵기가 무작위로 렌더링되지 않게)")
    void appendAssistant_stripsMarkdownMarks() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L,
                "## 분석\n**의사소통과 정서적 기대의 충돌**이 반복된 것으로 보입니다. 별표 하나 *는 남습니다.");

        // 굵게(**)와 줄머리 제목(#)만 걷는다. 별표 하나는 건드리지 않는다.
        assertThat(response.getContent()).isEqualTo(
                "분석\n의사소통과 정서적 기대의 충돌이 반복된 것으로 보입니다. 별표 하나 *는 남습니다.");
    }

    // turn-2가 답변 끝에 붙이는 내부 메타데이터. 읽어서 사연에 옮기고 본문에서는 뗀다 —
    // 안 떼면 JSON이 그대로 말풍선에 찍히고 다음 턴 프롬프트에도 실린다.
    @Test
    @DisplayName("어시스턴트 응답 저장 - chat-meta는 사연에 옮기고 본문에서 뗀다")
    void appendAssistant_extractsChatMeta() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L,
                "상대는 아직 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.\n\n"
                        + "---chat-meta---\n{\"reunionDirection\":\"NEGATIVE\"}");

        assertThat(response.getContent())
                .isEqualTo("상대는 아직 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.")
                .doesNotContain("reunionDirection");
        assertThat(story.getReunionDirection()).isEqualTo(ReunionDirection.NEGATIVE);
    }

    // 저장과 프롬프트 조립이 갈라졌다 — 그 사이에 자유입력 판별이 낀다(ChipMatcher).
    private List<ChatMessage> sendAndBuild(Long userId, Long storyId, String content, String chipId) {
        messageTxService.appendUserMessageAndBuildPrompt(userId, storyId, content, chipId);
        return messageTxService.promptFor(storyId);
    }

    private Story story(Long id) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "id", id);
        return story;
    }

    private Message message(MessageRole role, String content) {
        return Message.builder().role(role).content(content).build();
    }

    // ── 추천 질문 칩 ────────────────────────────────────────────────

    private static final String CHIP_COMMON = "칩 공용 자리표시자";
    private static final String CHIP_MODULE = "연락 모듈 자리표시자";
    private static final String CHIP_MICRO = "CONTACT_NOW 마이크로 자리표시자";

    private ChipDefinition stubChip() {
        return stubChip("CONTACT_NOW", "CONTACT", DiagnosisContext.NONE);
    }

    private ChipDefinition stubChip(String id, String module, DiagnosisContext context) {
        ChipDefinition chip = new ChipDefinition(id, id + " 라벨", module,
                id + " 설명", "", ChipInteraction.DIRECT, null);
        given(chipStore.find(id)).willReturn(chip);
        given(chipStore.commonPrompt()).willReturn(CHIP_COMMON);
        given(chipStore.modulePrompt(chip)).willReturn(CHIP_MODULE);
        given(chipStore.microPrompt(chip)).willReturn(CHIP_MICRO);
        given(chipStore.diagnosisContext(chip)).willReturn(context);
        return chip;
    }

    private Assessment fullAssessment() {
        Assessment assessment = Assessment.builder()
                .storyId(10L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(30)
                .breakupType(BreakupType.BURNOUT)
                .factor(AssessmentFactor.of(FactorName.REPLACEMENT, FactorLevel.STRONG_UNFAVORABLE,
                        "상대에게 새 연인이 정착함", null, null))
                .relapseRisk(RelapseRisk.HIGH)
                .relapseReason("같은 갈등이 그대로다")
                .watchPoint(WatchPoint.of("상대가 먼저 연락함", "판단을 다시 본다"))
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "createdAt", java.time.LocalDateTime.now());
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(10L))
                .willReturn(Optional.of(assessment));
        return assessment;
    }

    private List<ChatMessage> chipTurn(Story story, String chipId) {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(Message.user(story, "물어볼게요", chipId)),
                        PageRequest.of(0, 20), false));
        return sendAndBuild(1L, 10L, "물어볼게요", chipId);
    }

    // 확률이 한 번 나오면 매 턴 그 숫자가 너무 강한 기준점이 되어, 새로 판단해야 하는 질문까지
    // 기존 결론을 유지해 설명하는 쪽으로 흐른다. 그래서 기본은 안 싣는다.
    @Test
    @DisplayName("진단 주입 - NONE 모듈은 분석을 읽지도 않는다")
    void diagnosisContext_noneLoadsNothing() {
        Story story = story(10L);
        stubChip("CONTACT_NOW", "CONTACT", DiagnosisContext.NONE);

        assertThat(chipTurn(story, "CONTACT_NOW")).extracting(ChatMessage::content)
                .noneMatch(c -> c.contains("최근 진단에서"));
        // 안 싣는 데서 그치지 않고 조회 자체를 건너뛴다 — 안 쓸 값을 매 턴 읽을 이유가 없다
        verify(assessmentRepository, never()).findFirstByStoryIdOrderByCreatedAtDesc(anyLong());
    }

    // 무엇이 바뀌면 판단이 바뀌는지를 다루는 자리. 확률 숫자는 굳이 필요 없다.
    @Test
    @DisplayName("진단 주입 - FACTORS_WATCH는 요인과 관찰 지점만 싣고 확률은 뺀다")
    void diagnosisContext_factorsWatch() {
        Story story = story(10L);
        stubChip("REUNION_CHANGE", "REUNION_CONDITION", DiagnosisContext.FACTORS_WATCH);
        fullAssessment();

        assertThat(chipTurn(story, "REUNION_CHANGE")).extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("상대에게 새 연인이 정착함") && c.contains("상대가 먼저 연락함"))
                .noneMatch(c -> c.contains("30%"))
                .noneMatch(c -> c.contains("재발 위험"));
    }

    @Test
    @DisplayName("진단 주입 - RELATIONSHIP은 재발 위험만 싣고 요인은 뺀다")
    void diagnosisContext_relationship() {
        Story story = story(10L);
        stubChip("OUTLOOK_CAN_WORK", "RELATIONSHIP_OUTLOOK", DiagnosisContext.RELATIONSHIP);
        fullAssessment();

        assertThat(chipTurn(story, "OUTLOOK_CAN_WORK")).extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("재발 위험") && c.contains("같은 갈등이 그대로다"))
                .noneMatch(c -> c.contains("30%"))
                .noneMatch(c -> c.contains("상대에게 새 연인이 정착함"));
    }

    @Test
    @DisplayName("진단 주입 - FULL은 확률부터 관찰 지점까지 전부 싣는다")
    void diagnosisContext_full() {
        Story story = story(10L);
        stubChip("DIAG_LOW", "DIAGNOSIS_EXPLAIN", DiagnosisContext.FULL);
        fullAssessment();

        assertThat(chipTurn(story, "DIAG_LOW")).extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("30%") && c.contains("상대에게 새 연인이 정착함")
                        && c.contains("재발 위험") && c.contains("상대가 먼저 연락함"));
    }

    // 새 사건은 기존 결론에 끌리지 않고 그 행동 자체가 얼마나 큰 변화인지부터 깨끗하게 본다.
    @Test
    @DisplayName("진단 주입 - 자유입력은 기본으로 안 싣는다")
    void diagnosisContext_freeInputLoadsNothing() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        message(MessageRole.USER, "걔 친구가 제 스토리를 봤어요")),
                        PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = sendAndBuild(1L, 10L, "걔 친구가 제 스토리를 봤어요", null);

        assertThat(prompt).extracting(ChatMessage::content).noneMatch(c -> c.contains("최근 진단에서"));
        verify(assessmentRepository, never()).findFirstByStoryIdOrderByCreatedAtDesc(anyLong());
    }

    // 행동 상담은 칩 쪽(ACTION 모듈)으로 분리했으므로 회차 묶음의 action까지 같이 실으면
    // 같은 일을 두 벌로 시킨다. 더하는 게 아니라 대신하는 것이 이 분기의 전부다.
    @Test
    @DisplayName("칩 턴 - 회차 규칙 묶음 대신 공용, 모듈, 마이크로 프롬프트가 실린다")
    void buildPrompt_chipTurnReplacesSectionBundle() {
        Story story = story(10L);
        stubChip();
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        Message.user(story, "지금 연락해도 될까요?", "CONTACT_NOW"),
                        message(MessageRole.ASSISTANT, "지난 답변"),
                        message(MessageRole.USER, "사연이야")), PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = sendAndBuild(
                1L, 10L, "지금 연락해도 될까요?", "CONTACT_NOW");

        assertThat(prompt).extracting(ChatMessage::content)
                .contains(CHIP_COMMON, CHIP_MODULE, CHIP_MICRO)
                .contains(FINAL_CHECK)          // 출력 직전 점검은 칩 턴에도 맨 끝에 남는다
                .doesNotContain(ANALYSIS)       // 회차 묶음은 통째로 빠진다
                .doesNotContain(QUESTION);      // 유저가 고른 질문에 답하는 턴이라 되묻기를 안 시킨다
    }

    // 재시도는 유저 메시지를 그대로 두고 프롬프트만 다시 조립한다. chipId가 요청에만 있고
    // 행에 없으면 재시도한 답변만 전문 모듈 없이 나온다.
    @Test
    @DisplayName("칩 턴 - 재시도해도 저장된 chipId로 같은 모듈이 다시 실린다")
    void prepareRetry_keepsChipModule() {
        Story story = story(10L);
        stubChip();
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        Message userMessage = Message.user(story, "지금 연락해도 될까요?", "CONTACT_NOW");
        ReflectionTestUtils.setField(userMessage, "id", 7L);
        Message fallback = Message.fallback(story);
        ReflectionTestUtils.setField(fallback, "id", 8L);
        // 첫 조회는 재시도 가능 판정(최신 2개), 그다음이 프롬프트 재조립용 대화 창이다.
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(fallback, userMessage), PageRequest.of(0, 2), false))
                .willReturn(new SliceImpl<>(List.of(userMessage), PageRequest.of(0, 20), false));

        MessageTxService.PreparedRetry prepared = messageTxService.prepareRetry(1L, 10L);

        assertThat(prepared.prompt()).extracting(ChatMessage::content)
                .contains(CHIP_COMMON, CHIP_MODULE, CHIP_MICRO);
    }

    // 칩은 질문 범위를 제한하는 장치가 아니라 전문 프롬프트로 가는 지름길이다.
    @Test
    @DisplayName("자유입력 - chipId가 없으면 기존 회차 규칙 그대로 돈다")
    void buildPrompt_freeInputKeepsTurnSections() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(
                        message(MessageRole.USER, "걔 친구가 제 스토리를 봤어요"),
                        message(MessageRole.ASSISTANT, "지난 답변"),
                        message(MessageRole.USER, "사연이야")), PageRequest.of(0, 20), false));

        List<ChatMessage> prompt = sendAndBuild(
                1L, 10L, "걔 친구가 제 스토리를 봤어요", null);

        assertThat(prompt).extracting(ChatMessage::content)
                .contains(ANALYSIS)
                .doesNotContain(CHIP_COMMON);
    }

    // 칩을 지운 뒤에도 열려 있던 화면에서 뒤늦게 올 수 있다. 기록만 오염되고 프롬프트에는
    // 어차피 안 실리므로 저장 단계에서 떨군다.
    @Test
    @DisplayName("카탈로그에 없는 chipId는 저장하지 않고 자유입력으로 다룬다")
    void unknownChipIdIsDropped() {
        Story story = story(10L);
        given(chipStore.find("사라진칩")).willReturn(null);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "안녕", "사라진칩");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getChipId()).isNull();
    }
}
