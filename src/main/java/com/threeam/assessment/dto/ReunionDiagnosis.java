package com.threeam.assessment.dto;

import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReplacementStage;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;

// LLM이 대화를 읽고 내려준 분석(파싱 결과). 확률(%)은 여기 없다.
// LLM은 유형(1층)과 요인 판정(2층)만 내리고, 최종 숫자는 백엔드(TypeBandScorer)가 계산한다.
// 예외: activeReunionOffer(상대의 유효한 만남/재회 제안)면 백엔드가 100으로 확정한다.
// summary는 없다 — 기억 요약은 채팅 사실 추출이 전담한다(주인 단일화).
public record ReunionDiagnosis(
        ReunionVerdict verdict,
        boolean activeReunionOffer,         // 상대가 먼저 만남/재회를 제안했고 철회되지 않음
        BreakupType breakupType,            // 잠금 판정(DATING 등)이면 null. 언제나 하나다
        String typeEvidence,                // 유형 판정 근거 한 줄
        JumpRule jumpRule,                  // 유형 대역을 교체하는 점프 규칙(없으면 NONE)
        List<FactorItem> factors,           // 고정 5슬롯 판정
        RelapseRisk relapseRisk,            // 유지 전망(성사 확률과 별개 축)
        String relapseReason,
        List<WatchItem> watchFor,           // 관찰 포인트 1~2개
        // 상담자가 물었는데 유저가 답하지 않은 것. 분석은 대화를 통째로 보므로 추가 호출
        // 없이 뽑을 수 있다. 화면의 "아직 모르는 것"이 고정 문구 대신 이걸 쓴다.
        List<String> unansweredQuestions,
        MatchProfileItem matchProfile,      // 사례 매칭용 분류(분류체계 어휘). 못 뽑으면 null
        // 관계 심리(애착 경향, 관계 패턴, 욕구 충돌) — 확률 계산에 쓰지 않는 "관계 이해용" 층.
        // verdict와 무관하게 채워질 수 있고, 읽을 재료가 없으면 null.
        RelationshipPsychology relationshipPsychology,
        String reason,
        List<String> newFacts) {            // 새로 드러난 사실 → StoryFact 원장에 append

    // 한 요인의 판정. stage는 대체자 불리의 세분(정황/정착) — 다른 요인은 null.
    public record FactorItem(FactorName name, FactorLevel level, String evidence,
                             String rationale, ReplacementStage stage) {
    }

    // "이게 확인되면 판이 바뀐다" — 행동 지시가 아니라 판독의 연장.
    public record WatchItem(String point, String effect) {
    }

    // 사례 매칭에 쓸 분류. 확률과 무관하며, 값은 전부 분류체계 사전의 어휘여야 한다.
    // 대화에 안 드러난 항목은 null — 지어내면 엉뚱한 사례에 걸린다.
    // subReasons는 순서가 뜻을 가진다: 0번이 이별을 당긴 방아쇠, 뒤는 밑에 깔린 요인.
    public record MatchProfileItem(String reason, List<String> subReasons, String dumper,
                                   String fault, String contactState, Integer monthsSinceBreakup,
                                   Integer datingMonths, String ageGroup, String gender,
                                   Boolean repeatBreakup, Boolean partnerHasNew) {
    }
}
