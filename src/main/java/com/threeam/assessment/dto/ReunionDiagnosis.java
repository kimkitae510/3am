package com.threeam.assessment.dto;

import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;

// LLM이 대화를 읽고 내려준 진단(파싱 결과). 확률(%)은 여기 없다.
// 감점/가점 항목만 판단하고, 최종 숫자는 백엔드(ReunionScorer)가 합산, 클램프한다.
public record ReunionDiagnosis(
        ReunionVerdict verdict,
        BreakupType breakupType,
        PartnerType partnerType,
        List<DeductionItem> deductions,
        List<DeductionItem> boosts,  // 가점 항목. 강한 긍정 신호(상대의 행동)에만 나온다
        String reason,
        String summary,           // 감정 흐름, 현재 상태 요약 → StoryMemory에 반영
        List<String> newFacts) {  // 새로 드러난 사실 → StoryFact 원장에 append

    // points: 움직일 양(양수). 백엔드가 부호를 붙여 합산한다(감점 음수, 가점 양수).
    public record DeductionItem(String signal, int points, String evidence) {
    }
}
