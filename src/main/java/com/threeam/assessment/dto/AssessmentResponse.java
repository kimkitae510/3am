package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.AssessmentReading;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;

// 분석 응답(v2). 유형과 요인 판정은 화면 표기용 한국어 라벨로 내린다 —
// 프론트가 enum 상수를 다시 번역하지 않게 하고, 라벨 사전을 한 곳(백엔드 enum)에만 둔다.
@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;      // 잠금 판정이면 null. 상대 제안 유효 시 100
    private final String breakupType;       // 이별 유형 라벨("충동형"). v1 데이터와 잠금 판정은 null
    private final String typeEvidence;      // 유형(또는 미련 단계) 판정 근거 한 줄
    private final String jumpRule;          // 점프 라벨("유저통보미련흔적" 등). 없으면 null
    private final String relapseRisk;       // 유지 전망 라벨("높음"). 없으면 null
    private final String relapseReason;
    // 관계 심리(애착 경향, 관계 패턴, 욕구 충돌) — 확률과 무관한 "관계 이해용" 층. 없으면 null.
    // 저장 구조(record)를 그대로 내린다 — 라벨이 이미 화면 표기용 한국어라 변환할 게 없다.
    private final RelationshipPsychology relationshipPsychology;
    private final String reason;
    private final List<FactorView> factors;
    private final List<WatchView> watchFor;
    // 상담자가 물었는데 답이 안 온 질문. 화면의 "아직 모르는 것"이 요인 슬롯의 고정 문구
    // 대신 이걸 쓴다 — 그 사연을 읽고 만든 질문이라 훨씬 구체적이다.
    private final List<String> unansweredQuestions;
    private final LocalDateTime createdAt;
    // 재시도까지 남은 초. 실패 쿨다운으로 막힌 응답에만 채워진다(그 외 null).
    // 시각이 아니라 남은 초를 주는 이유: 클라이언트 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
    private final Integer retryAfterSeconds;

    // 정밀 판독(2호출). 판정만 있고 판독 생성이 실패했거나 아직 없는 판정은 null —
    // 화면은 판정부만 보여준다.
    private Reading reading;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability, String breakupType,
                               String typeEvidence, String jumpRule, String relapseRisk,
                               String relapseReason, RelationshipPsychology relationshipPsychology,
                               String reason, List<FactorView> factors, List<WatchView> watchFor,
                               List<String> unansweredQuestions,
                               LocalDateTime createdAt, Integer retryAfterSeconds) {
        this.verdict = verdict;
        this.probability = probability;
        this.breakupType = breakupType;
        this.typeEvidence = typeEvidence;
        this.jumpRule = jumpRule;
        this.relapseRisk = relapseRisk;
        this.relapseReason = relapseReason;
        this.relationshipPsychology = relationshipPsychology;
        this.reason = reason;
        this.factors = factors;
        this.watchFor = watchFor;
        this.unansweredQuestions = unansweredQuestions;
        this.createdAt = createdAt;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AssessmentResponse withRetryAfterSeconds(int seconds) {
        return new AssessmentResponse(verdict, probability, breakupType, typeEvidence, jumpRule,
                relapseRisk, relapseReason, relationshipPsychology, reason, factors, watchFor,
                unansweredQuestions, createdAt, seconds);
    }

    // 판독은 판정 저장 뒤에 붙는다(2호출이 나중에 끝난다) — 세터 대신 부착 창구 하나만 연다.
    public AssessmentResponse withReading(Reading reading) {
        this.reading = reading;
        return this;
    }

    public static AssessmentResponse from(Assessment assessment) {
        List<FactorView> factors = assessment.getFactors().stream()
                .map(f -> new FactorView(f.getName().label(), f.getLevel().label(),
                        f.getEvidence(), f.getRationale(),
                        f.getStage() != null ? f.getStage().label() : null))
                .toList();
        List<WatchView> watchFor = assessment.getWatchPoints().stream()
                .map(w -> new WatchView(w.getPoint(), w.getEffect()))
                .toList();
        return new AssessmentResponse(
                assessment.getVerdict(),
                assessment.getProbability(),
                assessment.getBreakupType() != null ? assessment.getBreakupType().label() : null,
                assessment.getTypeEvidence(),
                assessment.getJumpRule() != null && assessment.getJumpRule() != com.threeam.assessment.entity.JumpRule.NONE
                        ? assessment.getJumpRule().label() : null,
                assessment.getRelapseRisk() != null ? assessment.getRelapseRisk().label() : null,
                assessment.getRelapseReason(),
                assessment.getRelationshipPsychology(),
                assessment.getReason(),
                factors,
                watchFor,
                List.copyOf(assessment.getUnansweredQuestions()),
                assessment.getCreatedAt(),
                null);
    }

    // 한 요인의 판정. level이 "중립"이고 evidence가 "근거 없음"이면 화면이
    // "이걸 알려주면 정확해져요" 안내로 바꿔 보여준다.
    @Getter
    public static class FactorView {
        private final String name;       // "상대신호"
        private final String level;      // "유리" | "중립" | "불리"
        private final String evidence;
        private final String rationale;
        private final String stage;      // 대체자 불리의 세분("정황"/"정착"). 그 외 null

        private FactorView(String name, String level, String evidence, String rationale,
                           String stage) {
            this.name = name;
            this.level = level;
            this.evidence = evidence;
            this.rationale = rationale;
            this.stage = stage;
        }
    }

    // 관찰 포인트 — "이게 확인되면 판이 바뀐다".
    @Getter
    public static class WatchView {
        private final String point;
        private final String effect;

        private WatchView(String point, String effect) {
            this.point = point;
            this.effect = effect;
        }
    }

    // 정밀 판독 뷰. 표지(판정 + 올린/막는 이유)와 여섯 장(상대의 지금 / 결심 / 남은 마음 /
    // 왜 멀어졌나 / 막는 것 / 다시 움직일 조건), 국면. 요인 어휘는 안 내린다(채점 내부 용어).
    // state는 내부 값이지만 함께 내린다(화면은 국면의 "현재 판독" 소계에만 번역해 쓴다).
    public record Reading(
            String overall,
            String coverRaise,
            String coverBlock,
            Section now,
            Section resolve,
            Section remain,
            String drift,
            String blocking,
            Reselect reselect,
            String phase,
            Map<String, String> chapterTitles,
            Delta delta,
            LocalDateTime createdAt) {

        public record Section(String state, String answer, String reading) {
        }

        public record Reselect(String state, String answer, String open, String route) {
        }

        // 문진(사실 보강) 재판정의 변동내역. LLM 서술이 아니라 두 판정의 결정론 diff다 —
        // 같은 재료면 같은 델타라 저장하지 않고 조회 때 계산한다.
        public record Delta(Integer probabilityFrom, Integer probabilityTo,
                            List<FactorDelta> factors) {
        }

        public record FactorDelta(String name, String from, String to) {
        }

        public static Reading from(AssessmentReading reading, Assessment current, Assessment base) {
            return new Reading(
                    reading.getOverall(),
                    reading.getCoverRaise(),
                    reading.getCoverBlock(),
                    new Section(reading.getNowState(), reading.getNowAnswer(),
                            reading.getNowReading()),
                    new Section(reading.getResolveState(), reading.getResolveAnswer(),
                            reading.getResolveReading()),
                    new Section(reading.getRemainState(), reading.getRemainAnswer(),
                            reading.getRemainReading()),
                    reading.getNarrative(),
                    reading.getBlocking(),
                    new Reselect(reading.getReselectState(), reading.getReselectAnswer(),
                            reading.getReselectOpen(), reading.getReselectRoute()),
                    reading.getPhase(),
                    reading.getChapterTitles(),
                    delta(current, base),
                    reading.getCreatedAt());
        }

        // 변동내역 — 직전 판정(base) 대비 확률과 요인 레벨의 변화만 추린다.
        // 아무것도 안 변했으면 null이 아니라 빈 목록을 내린다("가늠이 이미 정확했다"도 정보다).
        private static Delta delta(Assessment current, Assessment base) {
            if (base == null || base.getProbability() == null || current.getProbability() == null) {
                return null;
            }
            Map<FactorName, String> baseLevels = new EnumMap<>(FactorName.class);
            for (AssessmentFactor factor : base.getFactors()) {
                baseLevels.put(factor.getName(), factor.getLevel().label());
            }
            List<FactorDelta> changed = new ArrayList<>();
            for (AssessmentFactor factor : current.getFactors()) {
                String from = baseLevels.get(factor.getName());
                String to = factor.getLevel().label();
                if (from != null && !Objects.equals(from, to)) {
                    changed.add(new FactorDelta(factor.getName().label(), from, to));
                }
            }
            return new Delta(base.getProbability(), current.getProbability(), changed);
        }
    }
}
