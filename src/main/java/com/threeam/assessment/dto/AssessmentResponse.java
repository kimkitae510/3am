package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

// 진단 응답(v2). 유형과 요인 판정은 화면 표기용 한국어 라벨로 내린다 —
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
    private final String reason;
    private final List<FactorView> factors;
    private final List<WatchView> watchFor;
    private final LocalDateTime createdAt;
    // 재시도까지 남은 초. 실패 쿨다운으로 막힌 응답에만 채워진다(그 외 null).
    // 시각이 아니라 남은 초를 주는 이유: 클라이언트 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
    private final Integer retryAfterSeconds;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability, String breakupType,
                               String typeEvidence, String jumpRule, String relapseRisk,
                               String relapseReason,
                               String reason, List<FactorView> factors, List<WatchView> watchFor,
                               LocalDateTime createdAt, Integer retryAfterSeconds) {
        this.verdict = verdict;
        this.probability = probability;
        this.breakupType = breakupType;
        this.typeEvidence = typeEvidence;
        this.jumpRule = jumpRule;
        this.relapseRisk = relapseRisk;
        this.relapseReason = relapseReason;
        this.reason = reason;
        this.factors = factors;
        this.watchFor = watchFor;
        this.createdAt = createdAt;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AssessmentResponse withRetryAfterSeconds(int seconds) {
        return new AssessmentResponse(verdict, probability, breakupType, typeEvidence, jumpRule,
                relapseRisk, relapseReason, reason, factors, watchFor, createdAt, seconds);
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
                assessment.getReason(),
                factors,
                watchFor,
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
}
