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
        String periodLabel,      // 배지: "재회 네 달째"
        String reunionRecord) {

    public static SimilarCaseResponse from(ReunionCase source) {
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
                source.getReunionRecord());
    }
}
