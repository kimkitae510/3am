package com.threeam.match.dto;

import com.threeam.match.entity.ReunionCase;
import java.util.List;

// 유료 매칭의 사례 카드 한 장. 무료 매칭(SimilarCaseResponse)에 LLM 해설 두 줄이 붙는다.
//
// matchedTags는 그대로 규칙 기반(CaseScorer)이다. LLM에게 태그까지 맡기지 않은 이유:
// 태그는 유저가 이미 자기 입으로 말한 값만 담는다는 약속이라, 지어낼 여지를 두면 그 약속이 깨진다.
// LLM이 쓰는 건 similarity와 reading 두 줄뿐이고 근거는 사례 본문으로 제한된다.
//
// 유사도 점수는 여기도 안 내보낸다 — 숫자를 주면 "왜 62점이야"가 되고, 가중치는 조율해 나갈 값이다.
public record PickedCaseResponse(
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
        Integer datingMonths,
        Integer monthsSinceBreakup,
        List<String> matchedTags,
        String similarity,       // 내 상황과 겹치는 지점
        String reading) {        // 이 판이 그렇게 된 이유의 추측

    public static PickedCaseResponse from(ReunionCase source, List<String> matchedTags,
                                          String similarity, String reading) {
        return new PickedCaseResponse(
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
                matchedTags,
                similarity,
                reading);
    }
}
