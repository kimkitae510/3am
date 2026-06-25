package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.RelationshipPsychology;
import com.threeam.assessment.dto.ReunionDiagnosis.MatchProfileItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.AssessmentReading;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentReadingRepository;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.match.service.MatchProfileService;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import com.threeam.story.service.StoryIntakeService;
import java.time.Duration;
import java.time.LocalDate;
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

// 분석의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(AssessmentService)에서 일어나므로 커넥션을 점유하지 않는다.
@Service
@RequiredArgsConstructor
public class AssessmentTxService {

    private static final int HISTORY_WINDOW = 20;

    // 분석 프롬프트에 싣는 사실 원장 상한(최근 N개). 분석은 사실이 확률의 근거라 채팅(30)보다 넉넉히.
    private static final int FACT_INJECT_LIMIT = 50;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryFactRepository storyFactRepository;
    private final StoryFactService storyFactService;
    private final StoryIntakeRepository storyIntakeRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentReadingRepository readingRepository;
    private final MatchProfileService matchProfileService;
    private final TypeBandScorer scorer;

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
    // 조건이면 분석만 원하는 유저가 갇힌다. 또 실패하면 다시 쿨다운 — 시도 빈도만 캡된다.
    // 3분: 남은 시간을 카운트다운으로 보여주는 이상, 무작정 길게 잡으면 기다릴 마음이 사라진다.
    private static final Duration FAIL_RETRY_COOLDOWN = Duration.ofMinutes(3);

    // 분석 실패 재시도 가드: 실패는 후차감(미차감)이라, 같은 재료가 계속 같은 이유로 실패하면
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

    // tx1: 소유권 확인 + 재분석 가드 + 최근 대화 + 기억 요약을 모아 온다. 짧게 끝난다.
    @Transactional(readOnly = true)
    public AssessmentContext loadContext(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));

        // 재분석 가드: 지난 분석 이후 새 대화가 없으면 같은 재료라 거부한다(AS002).
        // "원장에 새 사실이 없어도 거부"(구 AS003)는 폐지 — temperature 0으로 같은 재료면 같은
        // 점수가 나와 출렁임 문제가 사라졌고, 채팅 추출이 사실을 놓쳤을 때 분석이 대화에서
        // 직접 사실을 뽑아 복구하는 길을 가드가 막는 부작용이 실측됐다(재회 성사 미기재 사건).
        // 기준은 마지막 분석과 마지막 헤어짐 확인(번복) 중 늦은 쪽 — 번복이 잠금 분석을 지우면
        // 그 분석을 소진시킨 메시지들이 미소진으로 되돌아가, 분석 시각만 보면 새 대화 없이
        // 분석과 번복이 무한 반복된다(실측).
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
                    // 유저가 화면에서 직접 적어준 사실도 새 대화와 동급의 새 재료다 —
                    // 채팅 없이 사실만 보태고 재분석하는 동선(부족 정보 직접 입력)을 허용한다.
                    if (!messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, since)
                            && !storyFactRepository.existsByStoryIdAndSourceAndCreatedAtAfter(
                                    storyId, FactSource.USER, since)) {
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
        // 상담자(시현)의 말은 싣지 않는다 — 그건 관측된 사실이 아니라 그때의 추측인데,
        // 분석이 그 해석 문장을 요인 근거로 옮겨 적는 오염이 실측됐다(골든셋 12).
        // 둘이 서로를 베끼면 판정이 늘 일치해 교차 검증도 무의미해진다. 분석의 재료는
        // 유저가 말한 사실과 원장(StoryFact)이고, 원장은 아래에서 따로 실린다.
        List<ChatMessage> conversation = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            if (message.getRole() == MessageRole.USER) {
                conversation.add(ChatMessage.user(message.getContent()));
            }
        }
        if (conversation.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NO_MESSAGES);
        }

        String intakeBlock = storyIntakeRepository.findByStoryId(storyId)
                .map(StoryIntakeService::describe)
                .orElse(null);
        return new AssessmentContext(factLines(storyId), conversation,
                todayLine(), previousDigest(lastAssessment.orElse(null)), intakeBlock);
    }

    // 루브릭 시간 규칙(5주/3개월, 소진형 1개월)의 기준점. 원장 기록일 추정에 맡기지 않고 명시한다.
    private String todayLine() {
        return "오늘 날짜: " + LocalDate.now() + ". 이별 경과 등 시간 계산은 이 날짜 기준이다.";
    }

    // 직전 분석 요지 — 새 사실 없이 유형이 분석마다 흔들리는 것을 막는 앵커.
    // 판정값만 싣는다(근거, 총평 제외) — 지난 판단의 서사까지 주면 반향실이 된다.
    // 유형이 없어도 점프 판이면 싣는다 — 유저 통보 판은 설계상 유형이 비는데, 유형 없음만으로
    // 앵커를 버리면 제일 흔들리는 판(미련 뚜렷/흔적 경계)이 매번 백지 재판정된다
    // (질문 한 줄 보태고 재분석했더니 +10 실측). v1 데이터(둘 다 없음)나 잠금 판정이면 null.
    private String previousDigest(Assessment last) {
        if (last == null) {
            return null;
        }
        boolean hasType = last.getBreakupType() != null;
        boolean hasJump = last.getJumpRule() != null && last.getJumpRule() != JumpRule.NONE;
        List<String> parts = new ArrayList<>();
        if (hasType) {
            parts.add("유형=" + last.getBreakupType().label());
        }
        if (last.getProbability() != null) {
            parts.add("확률=" + last.getProbability());
        }
        if (hasJump) {
            parts.add("점프 규칙: " + last.getJumpRule().label());
        }
        if (!last.getFactors().isEmpty()) {
            StringBuilder factors = new StringBuilder("요인:");
            for (AssessmentFactor factor : last.getFactors()) {
                factors.append(" ").append(factor.getName().label())
                        .append("=").append(factor.getLevel().label());
            }
            parts.add(factors.toString());
        }
        // 확률 앵커가 없는 판(v1, 잠금)은 유형/점프/요인을 싣지 않지만, 관계 심리 라벨은
        // verdict와 무관하게 싣는다 — 관계 구조는 진단 사이에 잘 안 변하는 값이라
        // 새 행동 근거 없이 출렁이면 안 된다(루브릭의 직전 판정 유지 규칙과 세트).
        if (!hasType && !hasJump) {
            parts.clear();
        }
        String psychology = psychologyDigest(last.getRelationshipPsychology());
        if (psychology != null) {
            parts.add(psychology);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "직전 분석 요지(참고 — 루브릭의 직전 분석 규칙 적용): " + String.join(", ", parts);
    }

    // 관계 심리는 라벨만 싣는다(설명 제외 — 지난 서사까지 주면 반향실이 된다).
    private String psychologyDigest(RelationshipPsychology psychology) {
        if (psychology == null) {
            return null;
        }
        // 보류값은 앵커로 싣지 않는다 — "판단보류"를 실으면 다음 진단이 그 값을 유지하려 해
        // 근거가 생겨도 판단을 안 하게 된다(앵커의 목적은 판정 유지지 보류 유지가 아니다).
        List<String> bits = new ArrayList<>();
        RelationshipPsychology.PatternItem pattern = psychology.interactionPattern();
        if (pattern != null && !RelationshipPsychology.PATTERN_UNDECIDED.equals(pattern.label())) {
            bits.add("패턴=" + pattern.label());
        }
        RelationshipPsychology.Attachment attachment = psychology.attachment();
        if (attachment != null) {
            if (judged(attachment.user())) {
                bits.add("애착(유저)=" + attachment.user().label());
            }
            if (judged(attachment.partner())) {
                bits.add("애착(상대)=" + attachment.partner().label());
            }
        }
        return bits.isEmpty() ? null : "관계심리: " + String.join(" ", bits);
    }

    private boolean judged(RelationshipPsychology.Style style) {
        return style != null && !RelationshipPsychology.ATTACHMENT_UNDECIDED.equals(style.label());
    }

    // tx2: 분석 결과 저장 + 새 사실 원장 append + 매칭 프로필 갱신.
    // 기억 요약은 여기서 건드리지 않는다 — 채팅 사실 추출이 전담한다(주인 단일화, v2).
    // 저장 엔티티를 돌려준다 — 판독(2호출)이 판정 id와 요인을 입력으로 쓴다.
    @Transactional
    public Assessment save(Long storyId, Assessment assessment,
                           List<String> newFacts, MatchProfileItem matchProfile) {
        Assessment saved = assessmentRepository.save(assessment);
        storyFactService.appendFacts(storyId, saved.getId(), newFacts);
        matchProfileService.append(storyId, matchProfile);
        return saved;
    }

    // tx3: 정밀 판독 저장 + 뷰 조립. 판정과 별도 트랜잭션 — 판독 2호출이 실패해도
    // 판정은 이미 커밋돼 있고, 화면은 판정만으로도 성립한다.
    // base(직전 확률 판정)는 변동내역의 비교 기준 — 저장은 참조 id만 하고 델타는 조회 때 계산한다.
    @Transactional
    public AssessmentResponse.Reading saveReading(Long storyId, Long assessmentId,
                                                  ReadingDraft draft) {
        // 직전 트랜잭션에서 막 저장한 판정이라 없을 수 없다 — 없다면 코드 결함이니 그대로 터뜨려
        // 호출부(판독 실패 삼킴)의 로그로 남긴다.
        Assessment current = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new IllegalStateException("판정 없음 assessmentId=" + assessmentId));
        Assessment base = assessmentRepository
                .findFirstByStoryIdAndIdLessThanAndProbabilityIsNotNullOrderByIdDesc(
                        storyId, assessmentId)
                .orElse(null);
        AssessmentReading reading = readingRepository.save(AssessmentReading.builder()
                .assessmentId(assessmentId)
                .baseAssessmentId(base != null ? base.getId() : null)
                .overall(draft.overall())
                .coverRaise(draft.coverRaise())
                .coverBlock(draft.coverBlock())
                .narrative(draft.drift())
                .nowState(draft.now().state())
                .nowAnswer(draft.now().answer())
                .nowReading(draft.now().reading())
                .resolveState(draft.resolve().state())
                .resolveAnswer(draft.resolve().answer())
                .resolveReading(draft.resolve().reading())
                .remainState(draft.remain().state())
                .remainAnswer(draft.remain().answer())
                .remainReading(draft.remain().reading())
                .blocking(draft.blocking())
                .reselectState(draft.reselect().state())
                .reselectAnswer(draft.reselect().answer())
                .reselectOpen(draft.reselect().open())
                .reselectRoute(draft.reselect().route())
                .phase(draft.phase())
                .chapterTitles(draft.chapterTitles())
                .build());
        return AssessmentResponse.Reading.from(reading, current, base);
    }

    // 유저가 "사귀는 중" 판정을 번복할 때 원장에 남기는 문장.
    // 분석 프롬프트(ReunionLlm)가 이 문장을 근거로 DATING 재판정을 멈춘다 — 문구를 바꾸면 프롬프트 규칙도 함께 바꿔야 한다.
    public static final String BREAKUP_CONFIRMED_FACT = "유저가 직접 확인함: 사귀는 중이 아니라 헤어진 상태다";

    // 마지막 판정이 "만나는 중"(DATING 또는 재회 성공 REUNITED)일 때만 받는다 —
    // 아무 때나 열어두면 원장에 무의미한 확인 기록이 쌓인다. 재회했다가 다시 헤어지는 경우도 이 창구다.
    // 유저가 "헤어진 게 맞다"고 정정하면 그 잠금 판정은 오판이므로 기록에서 지우고,
    // 직전 확률 분석이 다시 최신이 되게 한다(100% 번복과 같은 즉시 복귀 — 재분석 불필요).
    // 직전 확률 분석이 없으면(첫 분석부터 잠금) 빈 값 — 화면은 첫 분석 안내로 돌아간다.
    @Transactional
    public Optional<AssessmentResponse> confirmBreakup(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        // 잠금 판정은 연달아 쌓인다 — 번복하고 재분석했는데 또 잠금이 나오는 경로가 있다.
        // 최신 하나만 지우면 바로 아래 잠금이 올라와 화면이 그대로라 아무 일도 안 일어난
        // 것처럼 보인다(실측). 최신부터 이어지는 잠금 판정을 한 번에 다 걷어낸다.
        List<Assessment> all = assessmentRepository.findByStoryIdOrderByCreatedAtDesc(storyId);
        List<Assessment> locks = new ArrayList<>();
        for (Assessment a : all) {
            if (a.getVerdict() != ReunionVerdict.DATING
                    && a.getVerdict() != ReunionVerdict.REUNITED) {
                break;
            }
            locks.add(a);
        }
        if (locks.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NOT_DATING);
        }
        assessmentRepository.deleteAll(locks);
        storyFactService.appendCorrection(storyId, BREAKUP_CONFIRMED_FACT);
        return all.stream().skip(locks.size()).findFirst().map(AssessmentResponse::from);
    }

    // 유저가 "상대의 재회 제안 유효(100%)" 확정을 번복할 때 원장에 남기는 문장.
    // 이것도 ReunionLlm 프롬프트의 false 규칙과 짝 — 문구를 바꾸면 프롬프트도 함께 바꿔야 한다.
    public static final String OFFER_RETRACTED_FACT = "유저가 직접 확인함: 상대의 재회 제안은 더 이상 유효하지 않다";

    // 마지막 분석이 제안 확정(100%)일 때만 받는다. confirmBreakup과 같은 원리의 잠금 해제 창구.
    // 100은 합산 결과가 아니라 확정 표시일 뿐이라, 저장해 둔 신호들을 재합산하면 재분석(LLM 비용)
    // 없이 즉시 일반 확률로 되돌릴 수 있다. 원장 정정은 다음 분석의 오판(제안 재확정)을 막는다.
    @Transactional
    public AssessmentResponse retractOffer(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Assessment last = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .filter(a -> a.getVerdict() == ReunionVerdict.POSSIBLE
                        && Integer.valueOf(100).equals(a.getProbability()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_OFFER));
        last.retractOffer(scorer.apply(last.getBreakupType(), last.getBreakupTypeSecondary(),
                last.getJumpRule(), last.getFactors()));
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
            // 직접 입력분은 유저의 주장임을 표시한다 — 루브릭의 "유저 말보다 상대 행동 우선"이 걸리게.
            String marker = fact.getSource() == FactSource.USER ? " [유저 직접 입력]" : "";
            lines.add("(" + FACT_DATE.format(fact.getCreatedAt()) + ")" + marker + " " + fact.getFact());
        }
        return lines;
    }

}
