package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentConfidence;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import com.threeam.story.service.StoryMemoryService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 진단의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(AssessmentService)에서 일어나므로 커넥션을 점유하지 않는다.
@Service
@RequiredArgsConstructor
public class AssessmentTxService {

    private static final int HISTORY_WINDOW = 20;

    // 진단 프롬프트에 싣는 사실 원장 상한(최근 N개). 진단은 사실이 확률의 근거라 채팅(30)보다 넉넉히.
    private static final int FACT_INJECT_LIMIT = 50;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryMemoryService storyMemoryService;
    private final StoryFactRepository storyFactRepository;
    private final StoryFactService storyFactService;
    private final AssessmentRepository assessmentRepository;
    private final ReunionScorer scorer;

    // INSUFFICIENT 재시도 가드: 지난 근거부족 시점 이후 새 대화가 없으면 막는다(같은 재료 = 같은 답).
    // 표시는 stories.last_insufficient_at(DB)에 있어 재시작, 멀티인스턴스에서도 유지된다.
    @Transactional(readOnly = true)
    public boolean isInsufficientRetryBlocked(Long storyId) {
        LocalDateTime since = storyRepository.findById(storyId)
                .map(Story::getLastInsufficientAt)
                .orElse(null);
        return since != null && !messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, since);
    }

    @Transactional
    public void markInsufficient(Long storyId) {
        storyRepository.updateLastInsufficientAt(storyId, LocalDateTime.now());
    }

    @Transactional
    public void clearInsufficient(Long storyId) {
        storyRepository.updateLastInsufficientAt(storyId, null);
    }

    // 실패 재시도 가드가 발동하는 연속 실패 횟수. 1회는 재시도를 허용한다 —
    // 일시 장애(503, 타임아웃)는 한 번 더로 복구될 수 있어서.
    private static final int FAIL_STREAK_LIMIT = 2;

    // 차단은 이 시간 동안만이다 — 새 대화 없이도 쿨다운이 지나면 다시 열어준다.
    // 생성 불량(정상 종료인데 본문 잘림)은 시간이 지나면 성공하기도 해서, 새 대화만 해제
    // 조건이면 진단만 원하는 유저가 갇힌다. 또 실패하면 다시 쿨다운 — 시도 빈도만 캡된다.
    // 3분: 남은 시간을 카운트다운으로 보여주는 이상, 무작정 길게 잡으면 기다릴 마음이 사라진다.
    private static final Duration FAIL_RETRY_COOLDOWN = Duration.ofMinutes(3);

    // 진단 실패 재시도 가드: 실패는 후차감(미차감)이라, 같은 재료가 계속 같은 이유로 실패하면
    // 무한 무료 LLM 호출이 된다(실측). 같은 재료 연속 2회 실패면 새 대화나 쿨다운 전까지 거부.
    // 반환값은 재시도까지 남은 초 — 0이면 차단 아님. 화면의 카운트다운이 이 값을 쓴다.
    @Transactional(readOnly = true)
    public int assessFailRetryBlockedSeconds(Long storyId) {
        Story story = storyRepository.findById(storyId).orElse(null);
        if (story == null || story.getLastAssessFailedAt() == null
                || story.getAssessFailStreak() < FAIL_STREAK_LIMIT) {
            return 0;
        }
        LocalDateTime retryableAt = story.getLastAssessFailedAt().plus(FAIL_RETRY_COOLDOWN);
        LocalDateTime now = LocalDateTime.now();
        if (!retryableAt.isAfter(now)) {
            return 0;
        }
        // 새 대화가 쌓였으면 재료가 바뀐 것이라 쿨다운과 무관하게 열어준다.
        if (messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, story.getLastAssessFailedAt())) {
            return 0;
        }
        // 올림 — 1.2초 남았는데 1초로 내려주면 화면이 0을 찍은 뒤에도 서버가 아직 막는다.
        return (int) Math.ceil(Duration.between(now, retryableAt).toMillis() / 1000.0);
    }

    // 같은 재료(지난 실패 이후 새 대화 없음)의 실패만 연속으로 센다.
    // 재료가 바뀐 뒤의 첫 실패는 1부터 — 새 대화마다 한 번의 재시도 여지가 되살아난다.
    @Transactional
    public void markAssessFailed(Long storyId) {
        LocalDateTime prev = storyRepository.findById(storyId)
                .map(Story::getLastAssessFailedAt)
                .orElse(null);
        boolean sameMaterial = prev != null
                && !messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, prev);
        if (sameMaterial) {
            storyRepository.incrementAssessFailStreak(storyId, LocalDateTime.now());
        } else {
            storyRepository.restartAssessFailStreak(storyId, LocalDateTime.now());
        }
    }

    @Transactional
    public void clearAssessFailed(Long storyId) {
        storyRepository.clearAssessFailStreak(storyId);
    }

    // 히스토리 조회 전 소유권만 확인한다.
    @Transactional(readOnly = true)
    public void loadOwnership(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }

    // tx1: 소유권 확인 + 재진단 가드 + 최근 대화 + 기억 요약을 모아 온다. 짧게 끝난다.
    @Transactional(readOnly = true)
    public AssessmentContext loadContext(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));

        // 재진단 가드: 지난 진단 이후 새 대화가 없으면 같은 재료라 거부한다(AS002).
        // "원장에 새 사실이 없어도 거부"(구 AS003)는 폐지 — temperature 0으로 같은 재료면 같은
        // 점수가 나와 출렁임 문제가 사라졌고, 채팅 추출이 사실을 놓쳤을 때 진단이 대화에서
        // 직접 사실을 뽑아 복구하는 길을 가드가 막는 부작용이 실측됐다(재회 성사 미기재 사건).
        // 기준은 마지막 진단과 마지막 헤어짐 확인(번복) 중 늦은 쪽 — 번복이 잠금 진단을 지우면
        // 그 진단을 소진시킨 메시지들이 미소진으로 되돌아가, 진단 시각만 보면 새 대화 없이
        // 진단과 번복이 무한 반복된다(실측).
        Optional<Assessment> lastAssessment = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId);
        LocalDateTime lastAssessedAt = lastAssessment.map(Assessment::getCreatedAt).orElse(null);
        LocalDateTime lastConfirmedAt = storyFactRepository
                .findFirstByStoryIdAndFactOrderByIdDesc(storyId, BREAKUP_CONFIRMED_FACT)
                .map(StoryFact::getCreatedAt)
                .orElse(null);
        Stream.of(lastAssessedAt, lastConfirmedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .ifPresent(since -> {
                    if (!messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, since)) {
                        throw new BusinessException(ErrorCode.ASSESSMENT_NO_NEW_MESSAGES);
                    }
                });

        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, HISTORY_WINDOW))
                .getContent();
        if (recent.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NO_MESSAGES);
        }

        // 최신→과거로 왔으니 시간순으로 뒤집어 대화 순서를 복원한다.
        List<ChatMessage> conversation = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            conversation.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }

        String summary = storyMemoryRepository.findByStoryId(storyId)
                .map(StoryMemory::getSummary)
                .orElse(null);

        // 직전 진단의 유형을 프롬프트에 실어 판정 연속성을 준다 — 반증 없이 유형이 사라지는 것 방지.
        String previousAttachment = lastAssessment
                .filter(a -> a.getPartnerAttachment() != null)
                .map(a -> a.getPartnerAttachment().getLabel()
                        + (a.getAttachmentConfidence() == AttachmentConfidence.TENTATIVE ? "(추정)" : "(확정)"))
                .orElse(null);

        return new AssessmentContext(summary, factLines(storyId), conversation, previousAttachment);
    }

    // tx2: 진단 결과 저장 + 기억(감정 요약) 갱신 + 새 사실 원장 append.
    @Transactional
    public AssessmentResponse save(Long storyId, Assessment assessment, String newSummary,
                                   List<String> newFacts) {
        Assessment saved = assessmentRepository.save(assessment);
        storyMemoryService.upsert(storyId, newSummary);
        storyFactService.appendFacts(storyId, saved.getId(), newFacts);
        return AssessmentResponse.from(saved);
    }

    // 유저가 "사귀는 중" 판정을 번복할 때 원장에 남기는 문장.
    // 진단 프롬프트(ReunionLlm)가 이 문장을 근거로 DATING 재판정을 멈춘다 — 문구를 바꾸면 프롬프트 규칙도 함께 바꿔야 한다.
    public static final String BREAKUP_CONFIRMED_FACT = "유저가 직접 확인함: 사귀는 중이 아니라 헤어진 상태다";

    // 마지막 판정이 "만나는 중"(DATING 또는 재회 성공 REUNITED)일 때만 받는다 —
    // 아무 때나 열어두면 원장에 무의미한 확인 기록이 쌓인다. 재회했다가 다시 헤어지는 경우도 이 창구다.
    // 유저가 "헤어진 게 맞다"고 정정하면 그 잠금 판정은 오판이므로 기록에서 지우고,
    // 직전 확률 진단이 다시 최신이 되게 한다(100% 번복과 같은 즉시 복귀 — 재진단 불필요).
    // 직전 확률 진단이 없으면(첫 진단부터 잠금) 빈 값 — 화면은 첫 진단 안내로 돌아간다.
    @Transactional
    public Optional<AssessmentResponse> confirmBreakup(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Assessment last = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .filter(a -> a.getVerdict() == ReunionVerdict.DATING
                        || a.getVerdict() == ReunionVerdict.REUNITED)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_DATING));
        assessmentRepository.delete(last);
        storyFactService.appendCorrection(storyId, BREAKUP_CONFIRMED_FACT);
        return assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .map(AssessmentResponse::from);
    }

    // 유저가 "상대의 재회 제안 유효(100%)" 확정을 번복할 때 원장에 남기는 문장.
    // 이것도 ReunionLlm 프롬프트의 false 규칙과 짝 — 문구를 바꾸면 프롬프트도 함께 바꿔야 한다.
    public static final String OFFER_RETRACTED_FACT = "유저가 직접 확인함: 상대의 재회 제안은 더 이상 유효하지 않다";

    // 마지막 진단이 제안 확정(100%)일 때만 받는다. confirmBreakup과 같은 원리의 잠금 해제 창구.
    // 100은 합산 결과가 아니라 확정 표시일 뿐이라, 저장해 둔 신호들을 재합산하면 재진단(LLM 비용)
    // 없이 즉시 일반 확률로 되돌릴 수 있다. 원장 정정은 다음 진단의 오판(제안 재확정)을 막는다.
    @Transactional
    public AssessmentResponse retractOffer(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Assessment last = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .filter(a -> a.getVerdict() == ReunionVerdict.POSSIBLE
                        && Integer.valueOf(100).equals(a.getProbability()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_OFFER));
        last.retractOffer(scorer.apply(last.getDeductions()));
        storyFactService.appendCorrection(storyId, OFFER_RETRACTED_FACT);
        return AssessmentResponse.from(last);
    }

    // 프롬프트용: "(11/10) 상대가 먼저 이별 통보" — 상대 시점 표현("일주일 전")을 기록일로 보정할 수 있게.
    // 최근 FACT_INJECT_LIMIT개만 싣고(비용 상한), 시간순으로 뒤집어 오래된 것부터 나열한다.
    private List<String> factLines(Long storyId) {
        List<StoryFact> recent = storyFactRepository.findByStoryIdOrderByIdDesc(
                storyId, PageRequest.of(0, FACT_INJECT_LIMIT));
        List<String> lines = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            StoryFact fact = recent.get(i);
            lines.add("(" + FACT_DATE.format(fact.getCreatedAt()) + ") " + fact.getFact());
        }
        return lines;
    }

}
