package com.threeam.match.dto;

import com.threeam.match.entity.ReunionCase;
import java.util.List;

// 사례 카드 한 장. 유사도 점수는 내보내지 않는다 — 숫자를 주면 "왜 62점이야"가 되고,
// 가중치는 조율해 나갈 값이라 유저에게 약속할 수 있는 척도가 아니다.
public record SimilarCaseResponse(
        Long id,
        String story,
        String gender,
        String ageGroup,
        String reason,
        List<String> subReasons,
        String dumper,
        String contactState,
        String outcome,          // 성공 / 실패 / 성공후재이별
        String periodLabel,      // "재회 네 달째" 같은 시점 프로즈
        String reunionRecord,
        Integer datingMonths,        // 만난 기간(개월) — 카드 메타 줄용, 미상이면 null
        Integer monthsSinceBreakup,  // 이별 후 경과(개월)
        String matchedOn) {          // 왜 이 사례가 나왔는지: "이별 사유, 지금 연락 상태" (없으면 null)

    public static SimilarCaseResponse from(ReunionCase source, String matchedOn) {
        return new SimilarCaseResponse(
                source.getId(),
                source.getStory(),
                source.getGender(),
                source.getAgeGroup(),
                source.getReason(),
                source.subReasonList(),
                source.getDumper(),
                source.getContactState(),
                source.getOutcome(),
                source.getPeriodLabel(),
                source.getReunionRecord(),
                source.getDatingMonths(),
                source.getMonthsSinceBreakup(),
                matchedOn);
    }
}
