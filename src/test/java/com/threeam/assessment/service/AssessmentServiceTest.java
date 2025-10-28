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
    private SafetyScanner safetyScanner;

    @Mock
    private ReunionLlm reunionLlm;

    @Mock
    private ReunionScorer scorer;

    @Mock
    private AssessmentRepository assessmentRepository;

    @InjectMocks
    private AssessmentService assessmentService;

    private static final AssessmentContext CONTEXT =
            new AssessmentContext("요약", List.of(ChatMessage.user("걔가 먼저 헤어지자 했어")));

    @Test
    @DisplayName("진단 - POSSIBLE이면 LLM 감점을 백엔드가 합산해 확률을 낸다")
    void assess_possible() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(safetyScanner.isDanger(anyList())).willReturn(false);
        given(reunionLlm.diagnose(eq("요약"), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.POSSIBLE, BreakupType.REGRETTER, PartnerType.AMBIVALENT,
                        List.of(new DeductionItem("상대가 먼저 통보", 15, "근거")), "총평", "갱신요약")));
        given(scorer.apply(anyList())).willReturn(20);
        given(txService.save(eq(10L), any(Assessment.class), any()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(response.getProbability()).isEqualTo(20);
        assertThat(response.getMyBreakupType()).isEqualTo("후회형");
        assertThat(response.getDeductions()).hasSize(1);
        assertThat(response.getDeductions().get(0).getDelta()).isEqualTo(-15); // 양수 points → 음수 delta
    }

    @Test
    @DisplayName("진단 - 위기 키워드가 걸리면 LLM을 호출하지 않고 DANGER로 단락한다")
    void assess_dangerShortCircuit() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(safetyScanner.isDanger(anyList())).willReturn(true);
        given(txService.save(eq(10L), any(Assessment.class), any()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.DANGER);
        assertThat(response.getProbability()).isNull();
        verify(reunionLlm, never()).diagnose(any(), anyList());
        verify(scorer, never()).apply(anyList());
    }

    @Test
    @DisplayName("진단 - LET_GO(졸업)면 확률 없이 저장하고 합산하지 않는다")
    void assess_letGo() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(safetyScanner.isDanger(anyList())).willReturn(false);
        given(reunionLlm.diagnose(eq("요약"), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.LET_GO, BreakupType.SELF_BLAMER, PartnerType.DECISIVE,
                        List.of(), "놓아줄 때야", "갱신요약")));
        given(txService.save(eq(10L), any(Assessment.class), any()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.LET_GO);
        assertThat(response.getProbability()).isNull();
        verify(scorer, never()).apply(anyList());
    }

    @Test
    @DisplayName("진단 - 없거나 남의 사연이면 STORY_NOT_FOUND (LLM 호출 없음)")
    void assess_storyNotFound() {
        given(txService.loadContext(1L, 10L))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(reunionLlm, never()).diagnose(any(), anyList());
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
