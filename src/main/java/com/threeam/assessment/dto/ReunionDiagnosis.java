package com.threeam.assessment.dto;

import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;

// LLM이 대화를 읽고 내려준 진단(파싱 결과). 확률(%)은 여기 없다.
// 감점/가점 항목만 판단하고, 최종 숫자는 백엔드(ReunionScorer)가 합산, 클램프한다.
// 예외: activeReunionOffer(상대의 유효한 만남/재회 제안)면 백엔드가 100으로 확정한다.
public record ReunionDiagnosis(
        ReunionVerdict verdict,
        boolean activeReunionOffer,         // 상대가 먼저 만남/재회를 제안했고 철회되지 않음
        List<DeductionItem> deductions,
        List<DeductionItem> boosts,
        MatchProfileItem matchProfile,      // 사례 매칭용 분류(분류체계 어휘). 못 뽑으면 null
        String reason,
        String summary,           // 감정 흐름, 현재 상태 요약 → StoryMemory에 반영
        List<String> newFacts) {  // 새로 드러난 사실 → StoryFact 원장에 append

    // points: 움직일 양(양수). 백엔드가 부호를 붙여 합산한다(감점 음수, 가점 양수).
    // rationale: 이 사실이 왜 확률을 움직이는지(판독 메커니즘) — 유저 납득용. 없으면 null.
    public record DeductionItem(String signal, int points, String evidence, String rationale) {
    }

    // 사례 매칭에 쓸 분류. 확률과 무관하며, 값은 전부 분류체계 사전의 어휘여야 한다.
    // 대화에 안 드러난 항목은 null — 지어내면 엉뚱한 사례에 걸린다.
    // subReasons는 순서가 뜻을 가진다: 0번이 이별을 당긴 방아쇠, 뒤는 밑에 깔린 요인.
    public record MatchProfileItem(String reason, List<String> subReasons, String dumper,
                                   String fault, String contactState, Integer monthsSinceBreakup,
                                   Integer datingMonths, String ageGroup, String gender,
                                   Boolean repeatBreakup) {
    }
}
