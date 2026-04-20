package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import com.threeam.story.service.StoryMemoryService;
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
    private StoryMemoryService storyMemoryService;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ReunionScorer scorer;

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
    @DisplayName("진단 저장 - 새 요약을 기억 서비스로 위임한다(빈 요약 처리는 서비스 책임)")
    void save_delegatesSummary() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));

        txService.save(STORY_ID, savedAssessment(null), "감정이 안정되어 가는 중", null);

        verify(storyMemoryService).upsert(STORY_ID, "감정이 안정되어 가는 중");
        verifyNoInteractions(storyMemoryRepository);   // 쓰기는 서비스 경유, 직접 접근 없음
    }

    private Story storyWithFailure(int streak, LocalDateTime failedAt) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "assessFailStreak", streak);
        ReflectionTestUtils.setField(story, "lastAssessFailedAt", failedAt);
        return story;
    }

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2025, 11, 10, 12, 0);

    @Test
    @DisplayName("실패 가드 - 같은 재료 연속 2회 실패면 쿨다운 동안 막는다")
    void failGuard_blocksAfterStreakWithoutNewMessage() {
        LocalDateTime recentFail = LocalDateTime.now().minusMinutes(2);
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, recentFail)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, recentFail)).willReturn(false);

        // 3분 쿨다운 중 2분이 지났으니 남은 시간은 60초 안쪽 — 화면 카운트다운이 이 값을 쓴다.
        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isBetween(1, 60);
    }

    @Test
    @DisplayName("실패 가드 - 1회 실패까지는 재시도를 허용한다(일시 장애 복구 여지)")
    void failGuard_allowsSingleFailure() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(1, LocalDateTime.now().minusMinutes(2))));

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 가드 - 연속 2회여도 새 대화가 생기면 다시 허용한다")
    void failGuard_allowsAfterNewMessage() {
        LocalDateTime recentFail = LocalDateTime.now().minusMinutes(2);
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, recentFail)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, recentFail)).willReturn(true);

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 가드 - 새 대화가 없어도 쿨다운(3분)이 지나면 다시 허용한다")
    void failGuard_allowsAfterCooldown() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, LocalDateTime.now().minusMinutes(4))));

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 표시 - 지난 실패 이후 새 대화가 없으면 연속 카운트를 올린다")
    void markFailed_incrementsOnSameMaterial() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(1, FAILED_AT)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, FAILED_AT)).willReturn(false);

        txService.markAssessFailed(STORY_ID);

        verify(storyRepository).incrementAssessFailStreak(eq(STORY_ID), any(LocalDateTime.class));
        verify(storyRepository, never()).restartAssessFailStreak(any(), any());
    }

    @Test
    @DisplayName("실패 표시 - 재료가 바뀐 뒤의 첫 실패는 1부터 다시 센다")
    void markFailed_restartsAfterNewMaterial() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, FAILED_AT)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, FAILED_AT)).willReturn(true);

        txService.markAssessFailed(STORY_ID);

        verify(storyRepository).restartAssessFailStreak(eq(STORY_ID), any(LocalDateTime.class));
        verify(storyRepository, never()).incrementAssessFailStreak(any(), any());
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
    @DisplayName("재진단 가드 - 새 대화가 있으면 통과하고 맥락을 정상 조립한다(원장 새 사실 여부는 보지 않음)")
    void loadContext_passesWithNewMessages() {
        givenOwnedStory();
        Assessment last = lastAssessment();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, last.getCreatedAt()))
                .willReturn(true);
        givenConversation();

        assertThatCode(() -> txService.loadContext(1L, STORY_ID)).doesNotThrowAnyException();
    }

    private StoryFact breakupConfirmedFact(LocalDateTime createdAt) {
        StoryFact fact = StoryFact.of(STORY_ID, AssessmentTxService.BREAKUP_CONFIRMED_FACT, null);
        ReflectionTestUtils.setField(fact, "createdAt", createdAt);
        return fact;
    }

    @Test
    @DisplayName("재진단 가드 - 번복(헤어짐 확인)이 진단보다 늦으면 번복 이후 새 대화를 요구한다(진단, 번복 루프 차단)")
    void loadContext_rejectsWhenNoNewMessagesAfterBreakupConfirm() {
        givenOwnedStory();
        Assessment last = lastAssessment(); // 2025-11-10 12:00
        LocalDateTime confirmedAt = LocalDateTime.of(2025, 11, 11, 3, 0);
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        given(storyFactRepository.findFirstByStoryIdAndFactOrderByIdDesc(
                STORY_ID, AssessmentTxService.BREAKUP_CONFIRMED_FACT))
                .willReturn(Optional.of(breakupConfirmedFact(confirmedAt)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, confirmedAt))
                .willReturn(false); // 늦은 쪽(번복 시각)을 기준으로 물어야 한다

        assertThatThrownBy(() -> txService.loadContext(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NO_NEW_MESSAGES);
    }

    @Test
    @DisplayName("재진단 가드 - 진단 기록이 없어도 번복 기록이 있으면 그 이후 새 대화를 요구한다(첫 진단부터 잠금이던 사연)")
    void loadContext_guardsWithOnlyBreakupConfirm() {
        givenOwnedStory();
        LocalDateTime confirmedAt = LocalDateTime.of(2025, 11, 11, 3, 0);
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());
        given(storyFactRepository.findFirstByStoryIdAndFactOrderByIdDesc(
                STORY_ID, AssessmentTxService.BREAKUP_CONFIRMED_FACT))
                .willReturn(Optional.of(breakupConfirmedFact(confirmedAt)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, confirmedAt))
                .willReturn(false);

        assertThatThrownBy(() -> txService.loadContext(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NO_NEW_MESSAGES);
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

    private Assessment datingAssessment() {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.DATING)
                .reason("아직 만나는 중")
                .build();
        ReflectionTestUtils.setField(assessment, "id", 77L);
        return assessment;
    }

    @Test
    @DisplayName("헤어짐 확인 - 잠금 판정을 지우고 직전 확률 진단으로 즉시 복귀한다")
    void confirmBreakup_deletesLockAndRestoresPrevious() {
        givenOwnedStory();
        Assessment dating = datingAssessment();
        // 1차 조회: 잠금 판정 확인, 2차 조회(삭제 후): 직전 확률 진단이 최신이 된다.
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(dating), Optional.of(lastAssessment()));

        var restored = txService.confirmBreakup(1L, STORY_ID);

        assertThat(restored).isPresent();
        assertThat(restored.get().getProbability()).isEqualTo(20); // 재진단 없이 직전 확률로
        verify(assessmentRepository).delete(dating);
        verify(storyFactService).appendCorrection(STORY_ID,
                AssessmentTxService.BREAKUP_CONFIRMED_FACT);
    }

    @Test
    @DisplayName("헤어짐 확인 - 직전 확률 진단이 없으면 빈 값(첫 진단 안내로 복귀)")
    void confirmBreakup_emptyWhenNoPrevious() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(datingAssessment()), Optional.empty());

        assertThat(txService.confirmBreakup(1L, STORY_ID)).isEmpty();
    }

    @Test
    @DisplayName("헤어짐 확인 - 마지막 판정이 DATING이 아니면 거부한다(원장 오염 방지)")
    void confirmBreakup_rejectsWhenNotDating() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(lastAssessment())); // POSSIBLE

        assertThatThrownBy(() -> txService.confirmBreakup(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_DATING);

        verifyNoInteractions(storyFactService);
    }

    @Test
    @DisplayName("헤어짐 확인 - 진단 기록이 아예 없어도 거부한다")
    void confirmBreakup_rejectsWithoutAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> txService.confirmBreakup(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_DATING);

        verifyNoInteractions(storyFactService);
    }

    private Assessment offerAssessment() {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(100)
                .reason("상대 제안 유효")
                .build();
        ReflectionTestUtils.setField(assessment, "id", 77L);
        return assessment;
    }

    @Test
    @DisplayName("제안 번복 - 100이면 저장된 신호의 재합산 값으로 즉시 되돌리고 원장에 정정을 남긴다")
    void retractOffer_recalculatesImmediately() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(offerAssessment()));
        given(scorer.apply(any())).willReturn(40);

        var response = txService.retractOffer(1L, STORY_ID);

        assertThat(response.getProbability()).isEqualTo(40); // 재진단(LLM) 없이 즉시 복귀
        verify(storyFactService).appendCorrection(STORY_ID,
                AssessmentTxService.OFFER_RETRACTED_FACT);
    }

    @Test
    @DisplayName("제안 번복 - 마지막 진단이 100이 아니면 거부한다")
    void retractOffer_rejectsWhenNotOffer() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(lastAssessment())); // POSSIBLE 20%

        assertThatThrownBy(() -> txService.retractOffer(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_OFFER);

        verifyNoInteractions(storyFactService);
    }

    @Test
    @DisplayName("제안 번복 - 진단 기록이 없어도 거부한다")
    void retractOffer_rejectsWithoutAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> txService.retractOffer(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_OFFER);

        verifyNoInteractions(storyFactService);
    }

    private void givenConversation() {
        Message message = Message.user(Story.builder().userId(1L).title("사연").build(), "걔가 먼저 헤어지자 했어");
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(STORY_ID), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(message), PageRequest.of(0, 20), false));
        given(storyMemoryRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(storyFactRepository.findByStoryIdOrderByIdDesc(eq(STORY_ID), any(Pageable.class)))
                .willReturn(List.of());
    }
}
