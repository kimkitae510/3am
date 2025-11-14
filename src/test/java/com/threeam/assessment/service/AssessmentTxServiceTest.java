package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import java.time.LocalDateTime;
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
class AssessmentTxServiceTest {

    private static final Long STORY_ID = 10L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private AssessmentRepository assessmentRepository;

    @InjectMocks
    private AssessmentTxService txService;

    private Assessment savedAssessment(Long id) {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(20)
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "id", id);
        return assessment;
    }

    @Test
    @DisplayName("진단 저장 - 새 사실을 저장된 진단의 id(출처)와 함께 원장 서비스로 넘긴다")
    void save_delegatesFactsWithAssessmentId() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));
        List<String> newFacts = List.of("일주일 전 상대에게서 연락 옴");

        txService.save(STORY_ID, savedAssessment(null), null, newFacts);

        verify(storyFactService).appendFacts(STORY_ID, 99L, newFacts);
    }

    @Test
    @DisplayName("진단 저장 - 요약이 비어 있으면 기억(감정 요약)을 건드리지 않는다")
    void save_skipsMemoryWhenSummaryBlank() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));

        txService.save(STORY_ID, savedAssessment(null), "  ", null);

        verifyNoInteractions(storyMemoryRepository);
    }

    private Assessment lastAssessment() {
        Assessment last = savedAssessment(77L);
        ReflectionTestUtils.setField(last, "createdAt", LocalDateTime.of(2025, 11, 10, 12, 0));
        return last;
    }

    private void givenOwnedStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(Story.builder().userId(1L).title("사연").build()));
    }

    @Test
    @DisplayName("재진단 가드 - 마지막 진단 이후 새 대화가 없으면 거부한다")
    void loadContext_rejectsWhenNoNewMessages() {
        givenOwnedStory();
        Assessment last = lastAssessment();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, last.getCreatedAt()))
                .willReturn(false);

        assertThatThrownBy(() -> txService.loadContext(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NO_NEW_MESSAGES);
    }

    @Test
    @DisplayName("재진단 가드 - 새 대화가 있어도 원장에 새 사실이 없으면 거부한다(확률 변동 근거 없음)")
    void loadContext_rejectsWhenNoNewFacts() {
        givenOwnedStory();
        Assessment last = lastAssessment();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, last.getCreatedAt()))
                .willReturn(true);
        given(storyFactRepository.existsNewFactSince(STORY_ID, last.getCreatedAt(), 77L))
                .willReturn(false);

        assertThatThrownBy(() -> txService.loadContext(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NO_NEW_FACTS);
    }

    @Test
    @DisplayName("재진단 가드 - 새 사실이 있으면 통과하고 맥락을 정상 조립한다")
    void loadContext_passesWithNewFacts() {
        givenOwnedStory();
        Assessment last = lastAssessment();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, last.getCreatedAt()))
                .willReturn(true);
        given(storyFactRepository.existsNewFactSince(STORY_ID, last.getCreatedAt(), 77L))
                .willReturn(true);
        givenConversation();

        assertThatCode(() -> txService.loadContext(1L, STORY_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("재진단 가드 - 첫 진단(기록 없음)은 가드 없이 통과한다")
    void loadContext_firstAssessmentSkipsGuard() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());
        givenConversation();

        assertThatCode(() -> txService.loadContext(1L, STORY_ID)).doesNotThrowAnyException();
        verify(messageRepository, never()).existsByStoryIdAndCreatedAtAfter(any(), any());
    }

    private void givenConversation() {
        Message message = Message.user(Story.builder().userId(1L).title("사연").build(), "걔가 먼저 헤어지자 했어");
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(STORY_ID), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message), PageRequest.of(0, 20), false));
        given(storyMemoryRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(storyFactRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of());
    }
}
