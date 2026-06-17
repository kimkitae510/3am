package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.JumpRule;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

// 공유 링크로 열리는 공개 분석 뷰. 본인 화면이 읽는 것과 같은 재료를 내린다 —
// 근거 문장을 가려봐야 총평이 이미 사연을 서사로 요약해 나가고 있어 기준이 서지 않았고,
// 무엇이 보이는지는 공유 버튼 옆 문구가 미리 말한다(가려서가 아니라 알려서 지킨다).
// 예외 둘: 중립(판단 안 됨) 요인은 본인 화면에선 "알려주면 정확해져요" 재료지만 남에겐
// 정보가 아니라 뺀다. 비슷한 사례는 유저 것이 아니라 서비스 자산이라 이 응답에 없다.
@Getter
public class SharedAssessmentResponse {

    private final Integer probability;
    private final String breakupType;   // 유형 라벨("충동형") — 공개 페이지의 이별 사유 카드 재료
    private final String typeEvidence;  // 유형 판정 근거 한 줄 — 이별 사유 카드의 사실 줄
    private final String jumpRule;      // 점프 라벨 — 이별 후 상황 카드 재료
    private final String relapseRisk;
    private final String relapseReason;
    private final RelationshipPsychology relationshipPsychology;
    private final String reason;        // 총평
    private final List<SharedFactor> factors;
    private final LocalDateTime createdAt;

    private SharedAssessmentResponse(Integer probability, String breakupType, String typeEvidence,
                                     String jumpRule, String relapseRisk, String relapseReason,
                                     RelationshipPsychology relationshipPsychology, String reason,
                                     List<SharedFactor> factors, LocalDateTime createdAt) {
        this.probability = probability;
        this.breakupType = breakupType;
        this.typeEvidence = typeEvidence;
        this.jumpRule = jumpRule;
        this.relapseRisk = relapseRisk;
        this.relapseReason = relapseReason;
        this.relationshipPsychology = relationshipPsychology;
        this.reason = reason;
        this.factors = factors;
        this.createdAt = createdAt;
    }

    public static SharedAssessmentResponse from(Assessment assessment) {
        List<SharedFactor> factors = assessment.getFactors().stream()
                .filter(f -> f.getLevel() != FactorLevel.NEUTRAL)
                .map(f -> new SharedFactor(f.getName().label(), f.getLevel().label(),
                        f.getEvidence(), f.getRationale(),
                        f.getStage() != null ? f.getStage().label() : null))
                .toList();
        return new SharedAssessmentResponse(
                assessment.getProbability(),
                assessment.getBreakupType() != null ? assessment.getBreakupType().label() : null,
                assessment.getTypeEvidence(),
                assessment.getJumpRule() != null && assessment.getJumpRule() != JumpRule.NONE
                        ? assessment.getJumpRule().label() : null,
                assessment.getRelapseRisk() != null ? assessment.getRelapseRisk().label() : null,
                assessment.getRelapseReason(),
                assessment.getRelationshipPsychology(),
                assessment.getReason(),
                factors,
                assessment.getCreatedAt());
    }

    @Getter
    public static class SharedFactor {
        private final String name;   // "상대신호"
        private final String level;  // "매우유리"~"매우불리"
        private final String evidence;
        private final String rationale;
        private final String stage;  // 대체자 세분("정황"/"정착"). 그 외 null

        private SharedFactor(String name, String level, String evidence, String rationale,
                             String stage) {
            this.name = name;
            this.level = level;
            this.evidence = evidence;
            this.rationale = rationale;
            this.stage = stage;
        }
    }
}
