package com.threeam.assessment.dto;

import com.threeam.assessment.entity.AttachmentConfidence;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.GuidanceKind;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;

// LLM이 대화를 읽고 내려준 진단(파싱 결과). 확률(%)은 여기 없다.
// 감점/가점 항목만 판단하고, 최종 숫자는 백엔드(ReunionScorer)가 합산, 클램프한다.
// 예외: activeReunionOffer(상대의 유효한 만남/재회 제안)면 백엔드가 100으로 확정한다.
public record ReunionDiagnosis(
        ReunionVerdict verdict,
        AttachmentStyle partnerAttachment,          // 상대 애착유형. 행동 근거 부족 시 null (유저 유형은 안 낸다)
        AttachmentConfidence attachmentConfidence,  // 판정 확신도. 유형이 null이면 null
        List<AttachmentSignalItem> attachmentSignals, // 판정 근거 행동 목록. 유형이 null이면 빈 목록
        boolean activeReunionOffer,         // 상대가 먼저 만남/재회를 제안했고 철회되지 않음
        List<DeductionItem> deductions,
        List<DeductionItem> boosts,
        List<GuidanceEntry> guidance,       // 행동 가이드(do/dont). POSSIBLE 외에는 빈 목록
        String reason,
        String summary,           // 감정 흐름, 현재 상태 요약 → StoryMemory에 반영
        List<String> newFacts) {  // 새로 드러난 사실 → StoryFact 원장에 append

    // points: 움직일 양(양수). 백엔드가 부호를 붙여 합산한다(감점 음수, 가점 양수).
    public record DeductionItem(String signal, int points, String evidence) {
    }

    // 애착유형 판정에 실제로 쓰인 행동 근거 하나(신호명 + 관찰된 행동).
    public record AttachmentSignalItem(String signal, String evidence) {
    }

    // 행동 가이드 한 항목. basis = 어떤 신호/유형에서 나온 조언인지(없으면 null).
    public record GuidanceEntry(GuidanceKind kind, String advice, String basis) {
    }
}
