package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssessmentService {

    // 위기 판정 시 확률 대신 내려주는 고정 안내. 안전은 확률 놀이의 대상이 아니다.
    private static final String SAFETY_MESSAGE =
            "지금은 재회보다 네 안전이 먼저야. 혼자 감당하지 말고 주변에 도움을 청해줘. "
                    + "자살예방 상담전화 109, 정신건강 상담전화 1577-0199로 언제든 연락할 수 있어.";

    private final AssessmentTxService txService;
    private final SafetyScanner safetyScanner;
    private final ReunionLlm reunionLlm;
    private final ReunionScorer scorer;
    private final AssessmentRepository assessmentRepository;

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 txService의 짧은 트랜잭션, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AssessmentResponse> assess(Long userId, Long storyId) {
        AssessmentContext context = txService.loadContext(userId, storyId);

        // 안전 우선: 위기 키워드가 걸리면 LLM에 묻지 않고 곧장 DANGER로 단락한다.
        if (safetyScanner.isDanger(context.rawTexts())) {
            return CompletableFuture.completedFuture(
                    txService.save(storyId, dangerAssessment(storyId), null));
        }

        return reunionLlm.diagnose(context.memorySummary(), context.conversation())
                .thenApply(diagnosis -> persist(storyId, diagnosis));
    }

    public List<AssessmentResponse> getHistory(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        return assessmentRepository.findByStoryIdOrderByCreatedAtDesc(storyId).stream()
                .map(AssessmentResponse::from)
                .toList();
    }

    private AssessmentResponse persist(Long storyId, ReunionDiagnosis diagnosis) {
        // LLM이 뒤늦게 위기를 짚었어도 안전 경로로 합류시킨다(확률·감점 무시).
        if (diagnosis.verdict() == ReunionVerdict.DANGER) {
            return txService.save(storyId, dangerAssessment(storyId), null);
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

        return txService.save(storyId, assessment, diagnosis.summary());
    }

    private Deduction toDeduction(DeductionItem item) {
        return Deduction.of(item.signal(), item.points(), item.evidence());
    }

    private Assessment dangerAssessment(Long storyId) {
        return Assessment.builder()
                .storyId(storyId)
                .verdict(ReunionVerdict.DANGER)
                .reason(SAFETY_MESSAGE)
                .build();
    }
}
