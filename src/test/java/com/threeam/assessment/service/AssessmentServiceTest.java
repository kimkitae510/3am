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

    private static final AssessmentContext CONTEXT =
            new AssessmentContext("요약", List.of(), List.of(ChatMessage.user("걔가 먼저 헤어지자 했어")));

    @Test
    @DisplayName("진단 - POSSIBLE이면 LLM 감점을 백엔드가 합산해 확률을 낸다")
    void assess_possible() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.POSSIBLE, BreakupType.REGRETTER, PartnerType.AMBIVALENT,
                        List.of(new DeductionItem("상대가 먼저 통보", 15, "근거")), "총평", "갱신요약", List.of("상대가 먼저 통보함"))));
        given(scorer.apply(anyList())).willReturn(20);
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(response.getProbability()).isEqualTo(20);
        assertThat(response.getMyBreakupType()).isEqualTo("후회형");
        assertThat(response.getDeductions()).hasSize(1);
        assertThat(response.getDeductions().get(0).getDelta()).isEqualTo(-15); // 양수 points → 음수 delta
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT(근거 부족)면 저장하지 않고 가이드만 돌려준다")
    void assess_insufficient() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null,
                        List.of(), "조금 더 들려줄래요?", "", List.of())));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getProbability()).isNull();
        assertThat(response.getReason()).isEqualTo("조금 더 들려줄래요?");
        verify(scorer, never()).apply(anyList());
        verify(txService, never()).save(any(), any(), any(), anyList()); // 히스토리에 저장 안 함
        // 근거 부족이어도 LLM 비용은 나갔으므로 차감된다
        verify(usageLimiter).recordDaily(UsageKind.ASSESSMENT, 1L);
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
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, List.of(), "가이드", "", List.of())));

        assessmentService.assess(1L, 10L).join();

        verify(usageLimiter).checkDaily(UsageKind.ASSESSMENT, 1L);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 10L);
        // 후차감: 처리 완료 시점에 1회 기록된다
        verify(usageLimiter).recordDaily(UsageKind.ASSESSMENT, 1L);
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
