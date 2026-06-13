package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.JumpRule;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

// 공유 링크로 열리는 공개 분석 뷰. 근거 문장(evidence, rationale, typeEvidence)은 사연이
// 녹아 있어 싣지 않는다 — 공개 페이지는 확률, 총평, 신호의 이름과 등급까지만 말한다.
// 중립(판단 안 됨) 요인도 뺀다 — 본인 화면에선 "알려주면 정확해져요" 재료지만 남에겐 정보가 아니다.
@Getter
public class SharedAssessmentResponse {

    private final Integer probability;
    private final String breakupType;   // 유형 라벨("충동형") — 공개 페이지의 이별 사유 카드 재료
    private final String jumpRule;      // 점프 라벨 — 이별 후 상황 카드 재료
    private final String reason;        // 총평
    private final List<SharedFactor> factors;
    private final LocalDateTime createdAt;

    private SharedAssessmentResponse(Integer probability, String breakupType, String jumpRule,
                                     String reason, List<SharedFactor> factors,
                                     LocalDateTime createdAt) {
        this.probability = probability;
        this.breakupType = breakupType;
        this.jumpRule = jumpRule;
        this.reason = reason;
        this.factors = factors;
        this.createdAt = createdAt;
    }

    public static SharedAssessmentResponse from(Assessment assessment) {
        List<SharedFactor> factors = assessment.getFactors().stream()
                .filter(f -> f.getLevel() != FactorLevel.NEUTRAL)
                .map(f -> new SharedFactor(f.getName().label(), f.getLevel().label(),
                        f.getStage() != null ? f.getStage().label() : null))
                .toList();
        return new SharedAssessmentResponse(
                assessment.getProbability(),
                assessment.getBreakupType() != null ? assessment.getBreakupType().label() : null,
                assessment.getJumpRule() != null && assessment.getJumpRule() != JumpRule.NONE
                        ? assessment.getJumpRule().label() : null,
                assessment.getReason(),
                factors,
                assessment.getCreatedAt());
    }

    @Getter
    public static class SharedFactor {
        private final String name;   // "상대신호"
        private final String level;  // "매우유리"~"매우불리"
        private final String stage;  // 대체자 세분("정황"/"정착"). 그 외 null

        private SharedFactor(String name, String level, String stage) {
            this.name = name;
            this.level = level;
            this.stage = stage;
        }
    }
}
