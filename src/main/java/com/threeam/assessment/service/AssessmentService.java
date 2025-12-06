package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.llm.LlmRole;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentService {

    // 사전 가드: 유저 메시지가 이보다 적으면 LLM 없이도 "근거 부족"이 자명하다.
    // 뻔한 실패에 LLM 비용과 일일 쿼터(2회)를 태우지 않는다.
    private static final int MIN_USER_TURNS = 3;

    // INSUFFICIENT를 받은 사연의 시각. 새 대화 없이 재시도하면 LLM 없이 거부한다 —
    // INSUFFICIENT를 쿼터 미차감으로 바꾸면서 생기는 무한 호출 구멍을 이걸로 막는다.
    // (인메모리 = 단일 인스턴스 전제. in-flight 잠금과 같은 전제이며, 재시작 시 초기화돼도 해는 없다.)
    private final Map<Long, LocalDateTime> insufficientAt = new ConcurrentHashMap<>();

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
            // 후차감: 여기서는 한도 검사만. 기록은 진단이 정상 처리된 뒤에 한다(LLM 장애 시 미차감).
            usageLimiter.checkDaily(UsageKind.ASSESSMENT, userId);

            AssessmentContext context = txService.loadContext(userId, storyId);
            long userTurns = context.conversation().stream()
                    .filter(message -> message.role() == LlmRole.USER)
                    .count();
            if (userTurns < MIN_USER_TURNS) {
                // LLM 비용이 없으므로 쿼터도 차감하지 않는다. 잠금만 풀고 안내를 돌려준다.
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId));
            }
            // 지난 INSUFFICIENT 이후 새 대화가 없으면 다시 물어봐도 같은 답이다 — LLM 없이 거부.
            LocalDateTime lastGuide = insufficientAt.get(storyId);
            if (lastGuide != null && !txService.hasNewMessageAfter(storyId, lastGuide)) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId));
            }
            return reunionLlm.diagnose(context.memorySummary(), context.knownFactLines(), context.conversation())
                    .thenApply(diagnosis -> {
                        AssessmentResponse response = persist(storyId, diagnosis);
                        if (diagnosis.verdict() == ReunionVerdict.INSUFFICIENT) {
                            // 진단을 제공하지 못했으니 쿼터를 깎지 않는다(유저 억울함 방지).
                            // 대신 시점을 기록해 새 대화 없는 재시도를 위에서 공짜로 막는다.
                            insufficientAt.put(storyId, LocalDateTime.now());
                        } else {
                            insufficientAt.remove(storyId);
                            recordUsageQuietly(userId);
                        }
                        return response;
                    })
                    .whenComplete((ignored, ex) ->
                            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId));
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, storyId);
            throw e;
        }
    }

    // 쿼터 기록 실패가 이미 저장된 진단 응답을 500으로 오염시키지 않게 격리한다.
    private void recordUsageQuietly(Long userId) {
        try {
            usageLimiter.recordDaily(UsageKind.ASSESSMENT, userId);
        } catch (RuntimeException e) {
            log.error("진단 쿼터 기록 실패 userId={}", userId, e);
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

    private static final String DEFAULT_GUIDE =
            "아직 진단하기엔 이야기가 부족해요. 어쩌다 헤어졌는지, 지금 연락은 되는지, "
                    + "상대와 최근 있었던 일을 조금만 더 들려줄래요?";

    // 사전 가드용 임시 응답. 히스토리에 저장하지 않는다(확률 추이 오염 방지).
    private AssessmentResponse insufficientGuide(Long storyId) {
        return AssessmentResponse.from(Assessment.builder()
                .storyId(storyId)
                .verdict(ReunionVerdict.INSUFFICIENT)
                .reason(DEFAULT_GUIDE)
                .build());
    }

    private AssessmentResponse persist(Long storyId, ReunionDiagnosis diagnosis) {
        // 근거 부족은 진단이 아니라 "대화를 더 해달라"는 안내다. 히스토리(확률 추이)를 오염시키지 않도록 저장하지 않고,
        // reason에 담긴 가이드만 임시 응답으로 돌려준다.
        if (diagnosis.verdict() == ReunionVerdict.INSUFFICIENT) {
            String guide = (diagnosis.reason() == null || diagnosis.reason().isBlank())
                    ? DEFAULT_GUIDE
                    : diagnosis.reason();
            Assessment transientResult = Assessment.builder()
                    .storyId(storyId)
                    .verdict(ReunionVerdict.INSUFFICIENT)
                    .reason(guide)
                    .build();
            return AssessmentResponse.from(transientResult);
        }

        // 감점(음수 delta)과 가점(양수 delta)을 한 컬렉션에 부호로 구분해 담는다.
        List<Deduction> deductions = new ArrayList<>(diagnosis.deductions().stream()
                .map(this::toDeduction)
                .toList());
        diagnosis.boosts().stream()
                .map(b -> Deduction.boostOf(b.signal(), b.points(), b.evidence()))
                .forEach(deductions::add);

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
