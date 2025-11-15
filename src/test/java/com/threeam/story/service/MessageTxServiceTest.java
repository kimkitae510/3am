package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessageTxServiceTest {

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
        assertThat(prompt).extracting(ChatMessage::role)
                .containsExactly(LlmRole.SYSTEM, LlmRole.USER);
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
        given(storyFactRepository.findByStoryIdOrderByIdAsc(10L)).willReturn(List.of(fact));

        List<ChatMessage> prompt = messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "안녕").prompt();

        assertThat(prompt.get(1).role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(prompt.get(1).content())
                .contains("기록된 사실")
                .contains("(11/10) 상대가 먼저 이별을 통보함");
    }

    @Test
    @DisplayName("프롬프트 조립 - 최신 진단이 있으면 설명용 데이터 블록(확률, 감점, 근거)을 시스템 메시지로 싣는다")
    void buildPrompt_includesLatestAssessment() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message(MessageRole.USER, "왜 이 진단이야?")),
                        PageRequest.of(0, 20), false));
        Assessment assessment = Assessment.builder()
                .storyId(10L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(20)
                .reason("솔직히 쉽지 않아.")
                .deduction(Deduction.of("읽씹당하는 중", 15, "메시지를 계속 안 읽는다고 함"))
                .build();
        ReflectionTestUtils.setField(assessment, "createdAt", java.time.LocalDateTime.now());
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(10L))
                .willReturn(Optional.of(assessment));

        List<ChatMessage> prompt = messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "왜 이 진단이야?").prompt();

        // 시스템(페르소나) + 시스템(진단 데이터) + 유저
        assertThat(prompt).extracting(ChatMessage::role)
                .containsExactly(LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.USER);
        assertThat(prompt.get(1).content())
                .contains("20%")
                .contains("읽씹당하는 중")
                .contains("메시지를 계속 안 읽는다고 함");
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
