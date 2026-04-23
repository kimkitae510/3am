package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentSignal;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.GuidanceItem;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.llm.LlmRole;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    private final AssessmentTxService txService;
    private final ReunionLlm reunionLlm;
    private final ReunionScorer scorer;
    private final AssessmentRepository assessmentRepository;
    private final UsageLimiter usageLimiter;
    // 진단 저장을 HttpClient 스레드가 아니라 우리 풀에서 돌린다(LlmCallbackConfig 참고).
    private final Executor llmCallbackExecutor;

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 txService의 짧은 트랜잭션, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AssessmentResponse> assess(Long userId, Long storyId) {
        // 이 유저가 이미 진단을 생성 중이면 거부(연타 차단 + 기억 upsert 레이스 방지 + 동시 발사 한도 우회 차단).
        usageLimiter.acquireInFlight(UsageKind.ASSESSMENT, userId);
        try {
            // 후차감: 여기서는 한도 검사만. 기록은 진단이 정상 처리된 뒤에 한다(LLM 장애 시 미차감).
            usageLimiter.checkDaily(UsageKind.ASSESSMENT, userId, 1);

            AssessmentContext context = txService.loadContext(userId, storyId);
            long userTurns = context.conversation().stream()
                    .filter(message -> message.role() == LlmRole.USER)
                    .count();
            if (userTurns < MIN_USER_TURNS) {
                // LLM 비용이 없으므로 쿼터도 차감하지 않는다. 잠금만 풀고 안내를 돌려준다.
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, TURNS_GUIDE));
            }
            // 지난 INSUFFICIENT 이후 새 대화가 없으면 다시 물어봐도 같은 답이다 — LLM 없이 거부.
            // 이 표시는 DB(stories.last_insufficient_at)에 있어 재시작, 멀티인스턴스에서도 유지된다.
            if (txService.isInsufficientRetryBlocked(storyId)) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, NO_BASIS_GUIDE));
            }
            // 실패 재시도 가드: 실패는 후차감(미차감)이라, 같은 재료가 계속 같은 이유(안전성 차단,
            // 응답 잘림 등)로 실패하면 무한 무료 LLM 호출 루프가 된다(실측). 연속 2회부터 LLM 없이 거부.
            int retryAfterSeconds = txService.assessFailRetryBlockedSeconds(storyId);
            if (retryAfterSeconds > 0) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                // 남은 초를 함께 내려 화면이 "잠시 뒤"가 아니라 실제 카운트다운을 보여주게 한다.
                return CompletableFuture.completedFuture(
                        insufficientGuide(storyId, FAIL_RETRY_GUIDE).withRetryAfterSeconds(retryAfterSeconds));
            }
            return reunionLlm.diagnose(context.memorySummary(), context.knownFactLines(),
                            context.conversation(), context.previousAttachment())
                    .thenApplyAsync(diagnosis -> {
                        AssessmentResponse response = persist(storyId, diagnosis);
                        // LLM 왕복이 정상 처리됐으니 실패 연속 카운트를 지운다(INSUFFICIENT도 실패가 아니라 판정).
                        clearAssessFailQuietly(storyId);
                        if (diagnosis.verdict() == ReunionVerdict.INSUFFICIENT) {
                            // 진단을 제공하지 못했으니 쿼터를 깎지 않는다(유저 억울함 방지).
                            // 대신 시점을 DB에 남겨 새 대화 없는 재시도를 위에서 공짜로 막는다.
                            txService.markInsufficient(storyId);
                        } else {
                            txService.clearInsufficient(storyId);
                            recordUsageQuietly(userId);
                        }
                        return response;
                    }, llmCallbackExecutor)
                    .whenComplete((ignored, ex) -> {
                        // 진단 실패(LLM 장애, 저장 실패)를 storyId, userId와 함께 남긴다 —
                        // 전역 핸들러 로그엔 맥락이 없어 "돈 깎였는데 결과 없음" CS를 추적할 수 없다.
                        if (ex != null) {
                            log.error("진단 처리 실패 storyId={} userId={}", storyId, userId, ex);
                            markAssessFailedQuietly(storyId);
                        }
                        usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                    });
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
            throw e;
        }
    }

    // 쿼터 기록 실패가 이미 저장된 진단 응답을 500으로 오염시키지 않게 격리한다.
    private void recordUsageQuietly(Long userId) {
        try {
            usageLimiter.recordDaily(UsageKind.ASSESSMENT, userId, 1);
        } catch (RuntimeException e) {
            log.error("진단 쿼터 기록 실패 userId={}", userId, e);
        }
    }

    // 표시 기록 실패가 정상 응답을 오염시키거나(clear), 잠금 해제를 막지 않게(mark) 격리한다.
    private void clearAssessFailQuietly(Long storyId) {
        try {
            txService.clearAssessFailed(storyId);
        } catch (RuntimeException e) {
            log.error("진단 실패 표시 해제 실패 storyId={}", storyId, e);
        }
    }

    private void markAssessFailedQuietly(Long storyId) {
        try {
            txService.markAssessFailed(storyId);
        } catch (RuntimeException e) {
            log.error("진단 실패 표시 기록 실패 storyId={}", storyId, e);
        }
    }

    // "만나는 중" 잠금을 유저가 직접 번복하는 창구. 오판이던 잠금 판정을 지우고
    // 직전 확률 진단으로 즉시 복귀시킨다(없으면 빈 값 — 첫 진단 안내로).
    public Optional<AssessmentResponse> confirmBreakup(Long userId, Long storyId) {
        return txService.confirmBreakup(userId, storyId);
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

    // 같은 재료로 진단 생성이 연속 실패해 재시도를 막은 경우. 실패는 차감되지 않았음을 함께 알린다.
    // 남은 시간은 문구에 적지 않는다 — retryAfterSeconds로 내려가 화면이 카운트다운으로 보여준다.
    // 문구에 "5분쯤 뒤"처럼 박아두면 쿨다운을 조정할 때마다 여기까지 같이 고쳐야 하고, 실제 남은
    // 시간과 어긋나기도 한다.
    private static final String FAIL_RETRY_GUIDE =
            "연이어 실패해서 잠시 쉬어가요. 실패한 진단은 횟수가 차감되지 않았어요.";

    private static final String DATING_GUIDE =
            "아직 만나고 있는 사이라면 재회 확률은 의미가 없어요. 지금 겪는 갈등은 대화에서 같이 풀어봐요.";

    private static final String REUNITED_GUIDE =
            "다시 만나게 됐네요. 여기서부터는 확률이 아니라 관계를 이어가는 이야기예요. 대화에서 함께해요.";

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
        // save()에 진단 저장과 요약 갱신, 원장 적재가 함께 묶여 있어 이 분기에선 newFacts도 같이 버려진다.
        // 유실은 아니다 — 이 판정 뒤엔 재진단 가드가 "대화를 더 하고 오라"고 막고, 그 대화를 채팅 사실
        // 추출이 같은 구간까지 훑어 원장에 넣는다. 버려지는 건 이번 호출이 뽑아둔 중복분뿐이라
        // save()를 쪼개면서까지 살릴 값어치는 없다고 봤다(이 주석이 없으면 매번 버그로 의심받는 자리다).
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

        // 사귀는 중(DATING)이거나 재회에 성공(REUNITED) — 둘 다 확률 계산을 구조적으로 건너뛴다.
        // LLM이 실수로 감점을 보냈어도 버린다. 애착유형과 총평, 원장은 그대로 저장 —
        // 저장해야 화면의 최신 결과가 이전 확률 대신 이 판정으로 교체된다.
        if (diagnosis.verdict() == ReunionVerdict.DATING || diagnosis.verdict() == ReunionVerdict.REUNITED) {
            String fallback = diagnosis.verdict() == ReunionVerdict.DATING ? DATING_GUIDE : REUNITED_GUIDE;
            String reason = (diagnosis.reason() == null || diagnosis.reason().isBlank())
                    ? fallback
                    : diagnosis.reason();
            Assessment assessment = Assessment.builder()
                    .storyId(storyId)
                    .verdict(diagnosis.verdict())
                    .partnerAttachment(diagnosis.partnerAttachment())
                    .attachmentConfidence(diagnosis.attachmentConfidence())
                    .attachmentSignals(toAttachmentSignals(diagnosis))
                    .reason(reason)
                    .build();
            return txService.save(storyId, assessment, diagnosis.summary(), diagnosis.newFacts());
        }

        // 감점(음수 delta)과 가점(양수 delta)을 한 컬렉션에 부호로 구분해 담는다.
        List<Deduction> deductions = new ArrayList<>(diagnosis.deductions().stream()
                .map(this::toDeduction)
                .toList());
        diagnosis.boosts().stream()
                .map(b -> Deduction.boostOf(b.signal(), b.points(), b.evidence(), b.rationale()))
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

        // 행동 가이드는 확률 진단의 부속이다 — POSSIBLE에서만 저장한다(다른 판정은 루브릭이 비우지만 방어).
        List<GuidanceItem> guidanceItems = diagnosis.guidance().stream()
                .map(g -> GuidanceItem.of(g.kind(), g.advice(), g.basis()))
                .toList();

        Assessment assessment = Assessment.builder()
                .storyId(storyId)
                .verdict(diagnosis.verdict())
                .probability(probability)
                .partnerAttachment(diagnosis.partnerAttachment())
                .attachmentConfidence(diagnosis.attachmentConfidence())
                .attachmentSignals(toAttachmentSignals(diagnosis))
                .reason(diagnosis.reason())
                .deductions(deductions)
                .guidanceItems(guidanceItems)
                .build();

        return txService.save(storyId, assessment, diagnosis.summary(), diagnosis.newFacts());
    }

    private Deduction toDeduction(DeductionItem item) {
        return Deduction.of(item.signal(), item.points(), item.evidence(), item.rationale());
    }

    private List<AttachmentSignal> toAttachmentSignals(ReunionDiagnosis diagnosis) {
        return diagnosis.attachmentSignals().stream()
                .map(s -> AttachmentSignal.of(s.signal(), s.evidence()))
                .toList();
    }
}
