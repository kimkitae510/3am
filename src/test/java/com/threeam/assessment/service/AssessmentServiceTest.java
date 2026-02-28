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
import com.threeam.assessment.dto.ReunionDiagnosis.AttachmentSignalItem;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.dto.ReunionDiagnosis.GuidanceEntry;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentConfidence;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.GuidanceKind;
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

    // 사전 가드(유저 발화 없음 거부)를 통과하는 기본 컨텍스트
    private static final AssessmentContext CONTEXT =
            new AssessmentContext("요약", List.of(), List.of(
                    ChatMessage.user("걔가 먼저 헤어지자 했어"),
                    ChatMessage.assistant("언제부터 그런 말이 나왔어?"),
                    ChatMessage.user("한 달 전부터 지쳤다고 하더라"),
                    ChatMessage.assistant("연락은 지금 어때?"),
                    ChatMessage.user("일주일째 읽씹이야")));

    private static final AssessmentContext SPARSE_CONTEXT =
            new AssessmentContext("", List.of(), List.of(ChatMessage.assistant("어서 와, 무슨 일이야?")));

    @Test
    @DisplayName("진단 - POSSIBLE이면 LLM 감점을 백엔드가 합산해 확률을 낸다")
    void assess_possible() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.POSSIBLE, AttachmentStyle.AVOIDANT,
                        AttachmentConfidence.CONFIRMED,
                        List.of(new AttachmentSignalItem("갈등 시 대화 회피", "감정 얘기 회피 패턴")),
                        false,
                        List.of(new DeductionItem("상대가 먼저 통보", 15, "근거", "통보한 쪽은 결심이 선행된 상태")),
                        List.of(new DeductionItem("상대가 먼저 연락", 10, "근거2", null)),
                        List.of(new GuidanceEntry(GuidanceKind.DONT, "지금 연락은 미뤄봐", "매달림 신호")),
                        "총평", "갱신요약", List.of("상대가 먼저 통보함"))));
        given(scorer.apply(anyList())).willReturn(20);
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(response.getProbability()).isEqualTo(20);
        assertThat(response.getPartnerAttachment()).isEqualTo("거부회피형"); // 애착유형 라벨(커뮤니티 용어)
        assertThat(response.getAttachmentConfidence()).isEqualTo("CONFIRMED");
        assertThat(response.getAttachmentSignals()).hasSize(1); // 판정 근거 목록도 응답까지 전달
        assertThat(response.getAttachmentSignals().get(0).getEvidence()).isEqualTo("감정 얘기 회피 패턴");
        assertThat(response.getDeductions()).hasSize(2);
        assertThat(response.getDeductions().get(0).getDelta()).isEqualTo(-15); // 감점: 양수 points → 음수 delta
        assertThat(response.getDeductions().get(0).getRationale()).isEqualTo("통보한 쪽은 결심이 선행된 상태"); // 판독 이유 전달
        assertThat(response.getDeductions().get(1).getDelta()).isEqualTo(10);  // 가점: 양수 delta로 합류
        assertThat(response.getGuidance()).hasSize(1); // 행동 가이드도 응답까지 전달
        assertThat(response.getGuidance().get(0).getKind()).isEqualTo(GuidanceKind.DONT);
    }

    @Test
    @DisplayName("진단 - 상대의 유효한 만남/재회 제안이 있으면 감점 합산 없이 확률 100으로 확정한다")
    void assess_activeOfferForcesFullProbability() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.POSSIBLE, AttachmentStyle.FEARFUL,
                        AttachmentConfidence.TENTATIVE,
                        List.of(new AttachmentSignalItem("밀당 반복", "잠수와 재연락 반복")),
                        true,
                        List.of(new DeductionItem("상대가 먼저 통보", 15, "근거", null)),
                        List.of(), List.of(),
                        "수락만 남았어", "", List.of())));
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getProbability()).isEqualTo(100);
        verify(scorer, never()).apply(anyList()); // 합산을 건너뛴다
        // 신호는 그대로 저장된다 — 유저가 제안을 번복하면 이 신호들의 재합산으로 즉시 되돌린다
        assertThat(response.getDeductions()).hasSize(1);
        assertThat(response.getDeductions().get(0).getDelta()).isEqualTo(-15);
    }

    @Test
    @DisplayName("진단 - DATING(사귀는 중)이면 확률 없이 저장하고, 애착유형은 남기며, 쿼터는 차감한다")
    void assess_datingLocksProbabilityButKeepsAttachment() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.DATING, AttachmentStyle.AVOIDANT,
                        AttachmentConfidence.TENTATIVE,
                        List.of(new AttachmentSignalItem("갈등 시 대화 회피", "감정 얘기 회피 패턴")),
                        // LLM이 실수로 확률 재료를 보냈어도 전부 무시돼야 한다(구조적 잠금)
                        true,
                        List.of(new DeductionItem("권태", 15, "근거", null)),
                        List.of(new DeductionItem("먼저 연락", 5, "근거2", null)),
                        List.of(new GuidanceEntry(GuidanceKind.DO, "실수로 보낸 가이드", null)),
                        "아직 만나는 중이면 재회 확률은 의미가 없어", "사귀는 중 갈등 상담",
                        List.of("유저와 상대는 아직 사귀는 중"))));
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.DATING);
        assertThat(response.getProbability()).isNull();          // activeReunionOffer=true여도 100이 안 된다
        assertThat(response.getDeductions()).isEmpty();          // 감점/가점 폐기
        assertThat(response.getGuidance()).isEmpty();            // 가이드는 확률 진단의 부속이라 함께 폐기
        assertThat(response.getPartnerAttachment()).isEqualTo("거부회피형");
        assertThat(response.getAttachmentSignals()).hasSize(1); // 유형 근거는 관계 상태와 무관하게 남는다
        verify(scorer, never()).apply(anyList());                // 확률 계산 자체를 건너뛴다
        verify(txService).save(eq(10L), any(Assessment.class), eq("사귀는 중 갈등 상담"), anyList()); // 원장/요약은 평소대로
        verify(usageLimiter).recordDaily(UsageKind.ASSESSMENT, 1L, 1); // 유형+총평을 제공한 정식 결과라 차감
    }

    @Test
    @DisplayName("진단 - REUNITED(재회 성공)면 확률 없이 저장하고 쿼터는 차감한다")
    void assess_reunitedSavesWithoutProbability() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.REUNITED, null, null, List.of(), false,
                        List.of(), List.of(), List.of(), "다시 만나게 됐네", "재회 성공", List.of("두 사람이 다시 만나기로 함"))));
        given(txService.save(eq(10L), any(Assessment.class), any(), anyList()))
                .willAnswer(inv -> AssessmentResponse.from(inv.getArgument(1)));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.REUNITED);
        assertThat(response.getProbability()).isNull();          // 목표 달성 상태 — 확률 산출 없음
        verify(scorer, never()).apply(anyList());
        verify(usageLimiter).recordDaily(UsageKind.ASSESSMENT, 1L, 1); // 정식 결과라 차감
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT(근거 부족)면 저장하지 않고 가이드만 돌려준다")
    void assess_insufficient() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, List.of(), false,
                        List.of(), List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getProbability()).isNull();
        assertThat(response.getReason()).isEqualTo("조금 더 들려줄래요?");
        verify(scorer, never()).apply(anyList());
        verify(txService, never()).save(any(), any(), any(), anyList()); // 히스토리에 저장 안 함
        // 진단을 제공하지 못했으니 쿼터를 깎지 않는다 (재시도 남발은 INSUFFICIENT 가드가 막는다)
        verify(usageLimiter, never()).recordDaily(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후 새 대화가 없으면 LLM 재호출 없이 안내만 돌려준다")
    void assess_insufficientRetryBlockedWithoutNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, List.of(), false,
                        List.of(), List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));
        // 1차엔 아직 표시 없음(false) → LLM 판정, 2차엔 표시됨(true) → LLM 없이 거부.
        given(txService.isInsufficientRetryBlocked(10L)).willReturn(false, true);

        assessmentService.assess(1L, 10L).join(); // 1차: LLM이 INSUFFICIENT 판정
        AssessmentResponse retry = assessmentService.assess(1L, 10L).join(); // 2차: 새 대화 없음

        assertThat(retry.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        verify(reunionLlm, org.mockito.Mockito.times(1)).diagnose(any(), anyList(), anyList()); // 2차는 미호출
        verify(usageLimiter, never()).recordDaily(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후라도 새 대화가 생기면 다시 LLM으로 진단한다")
    void assess_insufficientRetryAllowedWithNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, List.of(), false,
                        List.of(), List.of(), List.of(), "조금 더 들려줄래요?", "", List.of())));
        // 새 대화가 계속 있으니 표시가 있어도 재시도가 막히지 않는다(항상 false).
        given(txService.isInsufficientRetryBlocked(10L)).willReturn(false);

        assessmentService.assess(1L, 10L).join();
        assessmentService.assess(1L, 10L).join();

        verify(reunionLlm, org.mockito.Mockito.times(2)).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 유저 발화가 하나도 없으면 LLM 호출 없이 안내만, 쿼터도 안 깎고 잠금은 해제한다")
    void assess_preGateOnSparseConversation() {
        given(txService.loadContext(1L, 10L)).willReturn(SPARSE_CONTEXT);

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        // 발화 없음 안내(사전 가드)는 근거 부족 안내(LLM 판정)와 문구가 다르다
        assertThat(response.getReason()).contains("이야기가 없어요");
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList()); // LLM 비용 없음
        verify(usageLimiter, never()).recordDaily(any(), any(), org.mockito.ArgumentMatchers.anyInt());          // 쿼터 미차감
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
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
        verify(usageLimiter, never()).recordDaily(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - 같은 사연의 진단이 진행 중이면 접수를 거부한다(연타 차단)")
    void assess_inFlightRejected() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.ASSESSMENT, 1L);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        verify(usageLimiter, never()).checkDaily(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 일일 한도를 넘으면 QUOTA_EXCEEDED, 잠금을 해제하고 LLM을 호출하지 않는다")
    void assess_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).checkDaily(UsageKind.ASSESSMENT, 1L, 1);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        verify(reunionLlm, never()).diagnose(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("진단 - 완료(성공) 시 in-flight 잠금이 해제된다")
    void assess_releasesLockOnCompletion() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(reunionLlm.diagnose(eq("요약"), anyList(), anyList())).willReturn(CompletableFuture.completedFuture(
                new ReunionDiagnosis(ReunionVerdict.INSUFFICIENT, null, null, List.of(), false, List.of(), List.of(), List.of(), "가이드", "", List.of())));

        assessmentService.assess(1L, 10L).join();

        verify(usageLimiter).checkDaily(UsageKind.ASSESSMENT, 1L, 1);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        // INSUFFICIENT는 진단을 제공하지 못했으니 차감하지 않는다
        verify(usageLimiter, never()).recordDaily(UsageKind.ASSESSMENT, 1L, 1);
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
