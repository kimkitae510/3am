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

    private final AssessmentTxService txService;
    private final ReunionLlm reunionLlm;
    private final ReunionScorer scorer;
    private final AssessmentRepository assessmentRepository;

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 txService의 짧은 트랜잭션, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AssessmentResponse> assess(Long userId, Long storyId) {
        AssessmentContext context = txService.loadContext(userId, storyId);
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
}
