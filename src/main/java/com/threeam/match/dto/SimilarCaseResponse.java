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
        String outcome,          // 성공 / 실패
        String periodLabel,      // "재회 네 달째" 같은 시점 프로즈
        Integer datingMonths,        // 만난 기간(개월) — 미상이면 null
        Integer monthsSinceBreakup,  // 이별 후 경과(개월)
        // 나와 겹친 지점의 태그. 겹친 축만 담기므로 여기 실리는 값은 전부 유저가 이미 말한 것이다.
        // 값을 못 띄우는 축은 축 이름으로 내려온다(CaseScorer.matchedTags).
        List<String> matchedTags) {

    public static SimilarCaseResponse from(ReunionCase source, List<String> matchedTags) {
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
                source.getDatingMonths(),
                source.getMonthsSinceBreakup(),
                matchedTags);
    }
}
