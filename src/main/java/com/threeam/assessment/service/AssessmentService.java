package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssessmentService {

    private final AssessmentTxService txService;
    private final ReunionLlm reunionLlm;
    private final ReunionScorer scorer;
    private final AssessmentRepository assessmentRepository;
    private final UsageLimiter usageLimiter;

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 txService의 짧은 트랜잭션, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AssessmentResponse> assess(Long userId, Long storyId) {
        // 같은 사연의 진단 동시 실행 차단(연타 차단 + 기억 upsert 레이스 방지).
        usageLimiter.acquireInFlight(UsageKind.ASSESSMENT, storyId);
        try {
            // INSUFFICIENT(근거 부족)로 끝나도 LLM 비용은 이미 나갔으므로 차감은 유지된다.
            usageLimiter.consumeDaily(UsageKind.ASSESSMENT, userId);
        } catch (RuntimeException e) {
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId);
            throw e;
        }

        try {
            AssessmentContext context = txService.loadContext(userId, storyId);
            return reunionLlm.diagnose(context.memorySummary(), context.knownFactLines(), context.conversation())
                    .thenApply(diagnosis -> persist(storyId, diagnosis))
                    .whenComplete((ignored, ex) ->
                            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId));
        } catch (RuntimeException e) {
            // LLM 비용이 나가기 전에 실패(소유권 없음, 대화 없음 등) → 차감을 되돌리고 잠금 해제.
            usageLimiter.refundDaily(UsageKind.ASSESSMENT, userId);
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId);
            throw e;
        }
    }

    // 감점 목록(@ElementCollection, LAZY)을 매핑에서 읽으므로 트랜잭션 안이어야 한다.
    // (open-in-view: false — 트랜잭션 밖에서 접근하면 LazyInitializationException → 500)
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getHistory(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        return assessmentRepository.findByStoryIdOrderByCreatedAtDesc(storyId).stream()
                .map(AssessmentResponse::from)
                .toList();
    }

    private AssessmentResponse persist(Long storyId, ReunionDiagnosis diagnosis) {
        // 근거 부족은 진단이 아니라 "대화를 더 해달라"는 안내다. 히스토리(확률 추이)를 오염시키지 않도록 저장하지 않고,
        // reason에 담긴 가이드만 임시 응답으로 돌려준다.
        if (diagnosis.verdict() == ReunionVerdict.INSUFFICIENT) {
            String guide = (diagnosis.reason() == null || diagnosis.reason().isBlank())
                    ? "아직 진단하기엔 이야기가 부족해요. 어쩌다 헤어졌는지, 지금 연락은 되는지, "
                    + "상대와 최근 있었던 일을 조금만 더 들려줄래요?"
                    : diagnosis.reason();
            Assessment transientResult = Assessment.builder()
                    .storyId(storyId)
                    .verdict(ReunionVerdict.INSUFFICIENT)
                    .reason(guide)
                    .build();
            return AssessmentResponse.from(transientResult);
        }

        List<Deduction> deductions = diagnosis.deductions().stream()
                .map(this::toDeduction)
                .toList();

        // 확률은 POSSIBLE일 때만. 졸업(LET_GO)은 숫자 대신 놓아주라는 판정.
        Integer probability = diagnosis.verdict() == ReunionVerdict.POSSIBLE
                ? scorer.apply(deductions)
                : null;

        Assessment assessment = Assessment.builder()
                .storyId(storyId)
                .verdict(diagnosis.verdict())
                .probability(probability)
                .myBreakupType(diagnosis.breakupType())
                .partnerType(diagnosis.partnerType())
                .reason(diagnosis.reason())
                .deductions(deductions)
                .build();

        return txService.save(storyId, assessment, diagnosis.summary(), diagnosis.newFacts());
    }

    private Deduction toDeduction(DeductionItem item) {
        return Deduction.of(item.signal(), item.points(), item.evidence());
    }
}
