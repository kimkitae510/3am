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

    // 사전 가드: 유저 발화가 하나도 없으면 LLM 없이도 "근거 없음"이 자명하다.
    // 대화가 한 번이라도 있으면 LLM에 보낸다 — 부족 여부는 횟수가 아니라 내용(원장)이 정한다.
    private static final int MIN_USER_TURNS = 1;

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
        // 같은 사연의 진단 동시 실행 차단(연타 차단 + 기억 upsert 레이스 방지) + 유저 동시 생성 상한.
        usageLimiter.acquireInFlight(UsageKind.ASSESSMENT, userId, storyId);
        try {
            // 후차감: 여기서는 한도 검사만. 기록은 진단이 정상 처리된 뒤에 한다(LLM 장애 시 미차감).
            usageLimiter.checkDaily(UsageKind.ASSESSMENT, userId);

            AssessmentContext context = txService.loadContext(userId, storyId);
            long userTurns = context.conversation().stream()
                    .filter(message -> message.role() == LlmRole.USER)
                    .count();
            if (userTurns < MIN_USER_TURNS) {
                // LLM 비용이 없으므로 쿼터도 차감하지 않는다. 잠금만 풀고 안내를 돌려준다.
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId, storyId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, TURNS_GUIDE));
            }
            // 지난 INSUFFICIENT 이후 새 대화가 없으면 다시 물어봐도 같은 답이다 — LLM 없이 거부.
            LocalDateTime lastGuide = insufficientAt.get(storyId);
            if (lastGuide != null && !txService.hasNewMessageAfter(storyId, lastGuide)) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId, storyId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, NO_BASIS_GUIDE));
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
                    .whenComplete((ignored, ex) -> {
                        // 진단 실패(LLM 장애, 저장 실패)를 storyId, userId와 함께 남긴다 —
                        // 전역 핸들러 로그엔 맥락이 없어 "돈 깎였는데 결과 없음" CS를 추적할 수 없다.
                        if (ex != null) {
                            log.error("진단 처리 실패 storyId={} userId={}", storyId, userId, ex);
                        }
                        usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId, storyId);
                    });
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId, storyId);
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

    // "사귀는 중" 잠금을 유저가 직접 번복하는 창구. 즉석에서 확률을 만들어주지 않고
    // 원장에 확인 기록만 남긴다 — 확률은 헤어진 경위를 대화로 들은 다음 진단에서 열린다.
    public void confirmBreakup(Long userId, Long storyId) {
        txService.confirmBreakup(userId, storyId);
    }

    // "재회 제안 유효(100%)" 확정을 유저가 직접 번복하는 창구. 저장된 신호의 재합산 값으로
    // 즉시 되돌린 결과를 돌려준다(재진단 불필요).
    public AssessmentResponse retractOffer(Long userId, Long storyId) {
        return txService.retractOffer(userId, storyId);
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

    // 미진단 사유는 원인별로 갈라 말해준다 — "왜 안 되는지"를 유저가 스스로 고칠 수 있게.
    // 유저 발화가 아예 없는 경우(사전 가드).
    private static final String TURNS_GUIDE =
            "아직 들려주신 이야기가 없어요. 어쩌다 헤어졌는지, 지금 어떤 상황인지 "
                    + "먼저 들려주시면 진단할 수 있어요.";

    // 대화는 있었지만 확률을 매길 '사실'이 부족한 경우(LLM 판정, 원장 빈약): 무엇을 말해야 하는지 안내.
    private static final String NO_BASIS_GUIDE =
            "이야기는 들었지만 확률을 매길 만한 사실이 아직 부족해요. 어쩌다 헤어졌는지, "
                    + "상대가 최근 어떻게 행동했는지 같은 '있었던 일'을 들려줄래요?";

    private static final String DATING_GUIDE =
            "아직 만나고 있는 사이라면 재회 확률은 의미가 없어요. 지금 겪는 갈등은 대화에서 같이 풀어봐요.";

    // 사전 가드용 임시 응답. 히스토리에 저장하지 않는다(확률 추이 오염 방지).
    private AssessmentResponse insufficientGuide(Long storyId, String guide) {
        return AssessmentResponse.from(Assessment.builder()
                .storyId(storyId)
                .verdict(ReunionVerdict.INSUFFICIENT)
                .reason(guide)
                .build());
    }

    private AssessmentResponse persist(Long storyId, ReunionDiagnosis diagnosis) {
        // 근거 부족은 진단이 아니라 "대화를 더 해달라"는 안내다. 히스토리(확률 추이)를 오염시키지 않도록 저장하지 않고,
        // reason에 담긴 가이드만 임시 응답으로 돌려준다.
        if (diagnosis.verdict() == ReunionVerdict.INSUFFICIENT) {
            String guide = (diagnosis.reason() == null || diagnosis.reason().isBlank())
                    ? NO_BASIS_GUIDE
                    : diagnosis.reason();
            Assessment transientResult = Assessment.builder()
                    .storyId(storyId)
                    .verdict(ReunionVerdict.INSUFFICIENT)
                    .reason(guide)
                    .build();
            return AssessmentResponse.from(transientResult);
        }

        // 아직 사귀는 중(DATING) — 재회 확률은 이별 전제라 백엔드가 계산 자체를 건너뛴다(구조적 잠금).
        // LLM이 실수로 감점을 보냈어도 버린다. 애착유형과 총평, 원장(사귀는 중이라는 사실)은 그대로 저장 —
        // 저장해야 화면의 최신 결과가 이전 확률 대신 이 판정으로 교체된다.
        if (diagnosis.verdict() == ReunionVerdict.DATING) {
            String reason = (diagnosis.reason() == null || diagnosis.reason().isBlank())
                    ? DATING_GUIDE
                    : diagnosis.reason();
            Assessment assessment = Assessment.builder()
                    .storyId(storyId)
                    .verdict(ReunionVerdict.DATING)
                    .myAttachment(diagnosis.myAttachment())
                    .partnerAttachment(diagnosis.partnerAttachment())
                    .myAttachmentEvidence(diagnosis.myAttachmentEvidence())
                    .partnerAttachmentEvidence(diagnosis.partnerAttachmentEvidence())
                    .reason(reason)
                    .build();
            return txService.save(storyId, assessment, diagnosis.summary(), diagnosis.newFacts());
        }

        // 감점(음수 delta)과 가점(양수 delta)을 한 컬렉션에 부호로 구분해 담는다.
        List<Deduction> deductions = new ArrayList<>(diagnosis.deductions().stream()
                .map(this::toDeduction)
                .toList());
        diagnosis.boosts().stream()
                .map(b -> Deduction.boostOf(b.signal(), b.points(), b.evidence()))
                .forEach(deductions::add);

        // 확률은 POSSIBLE일 때만. 상대의 유효한 만남/재회 제안이 있으면 유저 수락만 남은
        // 상태라 감점 합산을 건너뛰고 100으로 확정한다(제안이 회수되면 다음 진단부터 일반 합산).
        // 신호들은 그대로 저장한다 — 유저가 제안을 번복하면(retract-offer) 재진단 없이
        // 이 신호들의 합산으로 즉시 되돌리기 위한 재료다. 100과 신호 합이 안 맞아 보이는 건
        // 화면의 확정 카드가 "제안이 없던 일이 되면 아래 신호로 다시 계산"이라고 설명한다.
        boolean offerConfirmed =
                diagnosis.verdict() == ReunionVerdict.POSSIBLE && diagnosis.activeReunionOffer();
        Integer probability = diagnosis.verdict() == ReunionVerdict.POSSIBLE
                ? (offerConfirmed ? 100 : scorer.apply(deductions))
                : null;

        Assessment assessment = Assessment.builder()
                .storyId(storyId)
                .verdict(diagnosis.verdict())
                .probability(probability)
                .myAttachment(diagnosis.myAttachment())
                .partnerAttachment(diagnosis.partnerAttachment())
                .myAttachmentEvidence(diagnosis.myAttachmentEvidence())
                .partnerAttachmentEvidence(diagnosis.partnerAttachmentEvidence())
                .reason(diagnosis.reason())
                .deductions(deductions)
                .build();

        return txService.save(storyId, assessment, diagnosis.summary(), diagnosis.newFacts());
    }

    private Deduction toDeduction(DeductionItem item) {
        return Deduction.of(item.signal(), item.points(), item.evidence());
    }
}
