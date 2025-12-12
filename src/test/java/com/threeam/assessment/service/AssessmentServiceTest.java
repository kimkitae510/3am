package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerAttachment;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock
    private AssessmentTxService txService;

    @Mock
    private ReunionLlm reunionLlm;

    @Mock
    private ReunionScorer scorer;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private UsageLimiter usageLimiter;

    @InjectMocks
    private AssessmentService assessmentService;

    // 사전 가드(유저 발화 3개 미만 거부)를 통과하는 기본 컨텍스트
    private static final AssessmentContext CONTEXT =
            new AssessmentContext("요약", List.of(), List.of(
                    ChatMessage.user("걔가 먼저 헤어지자 했어"),
                    ChatMessage.assistant("언제부터 그런 말이 나왔어?"),
                    ChatMessage.user("한 달 전부터 지쳤다고 하더라"),
                    ChatMessage.assistant("연락은 지금 어때?"),
                    ChatMessage.user("일주일째 읽씹이야")));

    private static final AssessmentContext SPARSE_CONTEXT =
            new AssessmentContext("", List.of(), List.of(ChatMessage.user("안녕")));

    @Test
    @DisplayName("진단 - POSSIBLE이면 LLM 감점을 백엔드가 합산해 확률을 낸다")
    void assess_possible() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.POSSIBLE, BreakupType.REGRETTER, PartnerType.AMBIVALENT,
                        PartnerAttachment.AVOIDANT,
                        List.of(new DeductionItem("상대가 먼저 통보", 15, "근거")),
                        List.of(new DeductionItem("상대가 먼저 연락", 10, "근거2")),
                        "총평", "갱신요약", List.of("상대가 먼저 통보함"))));
        given(scorer.apply(anyList())).willReturn(20);
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(response.getProbability()).isEqualTo(20);
        assertThat(response.getMyBreakupType()).isEqualTo("후회형");
        assertThat(response.getPartnerAttachment()).isEqualTo("거부회피형"); // 애착유형 라벨 매핑(커뮤니티 용어)
        assertThat(response.getDeductions()).hasSize(2);
        assertThat(response.getDeductions().get(0).getDelta()).isEqualTo(-15); // 감점: 양수 points → 음수 delta
        assertThat(response.getDeductions().get(1).getDelta()).isEqualTo(10);  // 가점: 양수 delta로 합류
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT(근거 부족)면 저장하지 않고 가이드만 돌려준다")
    void assess_insufficient() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, null,
                        List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getProbability()).isNull();
        assertThat(response.getReason()).isEqualTo("조금 더 들려줄래요?");
        verify(scorer, never()).apply(anyList());
        verify(txService, never()).save(any(), any(), any(), anyList()); // 히스토리에 저장 안 함
        // 진단을 제공하지 못했으니 쿼터를 깎지 않는다 (재시도 남발은 INSUFFICIENT 가드가 막는다)
        verify(usageLimiter, never()).recordDaily(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후 새 대화가 없으면 LLM 재호출 없이 안내만 돌려준다")
    void assess_insufficientRetryBlockedWithoutNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, null,
                        List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));
        given(txService.hasNewMessageAfter(eq(10L), any())).willReturn(false);

        assessmentService.assess(1L, 10L).join(); // 1차: LLM이 INSUFFICIENT 판정
        AssessmentResponse retry = assessmentService.assess(1L, 10L).join(); // 2차: 새 대화 없음

        assertThat(retry.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        verify(reunionLlm, org.mockito.Mockito.times(1)).diagnose(any(), anyList(), anyList()); // 2차는 미호출
        verify(usageLimiter, never()).recordDaily(any(), any());
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후라도 새 대화가 생기면 다시 LLM으로 진단한다")
    void assess_insufficientRetryAllowedWithNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, null,
                        List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));
        given(txService.hasNewMessageAfter(eq(10L), any())).willReturn(true);

        assessmentService.assess(1L, 10L).join();
        assessmentService.assess(1L, 10L).join();

        verify(reunionLlm, org.mockito.Mockito.times(2)).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 유저 발화 3개 미만이면 LLM 호출 없이 안내만, 쿼터도 안 깎고 잠금은 해제한다")
    void assess_preGateOnSparseConversation() {
        given(txService.loadContext(1L, 10L)).willReturn(SPARSE_CONTEXT);

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getReason()).contains("이야기가 부족");
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList()); // LLM 비용 없음
        verify(usageLimiter, never()).recordDaily(any(), any());          // 쿼터 미차감
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 10L);
    }

    @Test
    @DisplayName("진단 - 없거나 남의 사연이면 STORY_NOT_FOUND, LLM 호출도 쿼터 기록도 없다")
    void assess_storyNotFound() {
        given(txService.loadContext(1L, 10L))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList());
        // 후차감이라 성공 전에 실패하면 기록할 것이 없다. 잠금만 해제.
        verify(usageLimiter, never()).recordDaily(any(), any());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 10L);
    }

    @Test
    @DisplayName("진단 - 같은 사연의 진단이 진행 중이면 접수를 거부한다(연타 차단)")
    void assess_inFlightRejected() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.ASSESSMENT, 10L);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        verify(usageLimiter, never()).checkDaily(any(), any());
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 일일 한도를 넘으면 QUOTA_EXCEEDED, 잠금을 해제하고 LLM을 호출하지 않는다")
    void assess_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).checkDaily(UsageKind.ASSESSMENT, 1L);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 10L);
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 완료(성공) 시 in-flight 잠금이 해제된다")
    void assess_releasesLockOnCompletion() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, null, List.of(), List.of(), "가이드", "", List.of())));

        assessmentService.assess(1L, 10L).join();

        verify(usageLimiter).checkDaily(UsageKind.ASSESSMENT, 1L);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 10L);
        // INSUFFICIENT는 진단을 제공하지 못했으니 차감하지 않는다
        verify(usageLimiter, never()).recordDaily(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("히스토리 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void getHistory_storyNotFound() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND))
                .given(txService).loadOwnership(1L, 10L);

        assertThatThrownBy(() -> assessmentService.getHistory(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }
}
