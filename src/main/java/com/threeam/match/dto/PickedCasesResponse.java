package com.threeam.match.dto;

import java.util.List;

// 유료 매칭 응답. cases가 비면 이유를 함께 내려 화면이 "없음"과 "아직 못 찾음"을 갈라 말하게 한다.
// NO_PROFILE: 분석이 없거나 대화에서 이별 사유가 안 드러남
// NO_MATCH: 프로필은 있는데 하한을 넘는 사례가 없음 — 이 경우 쿼터를 안 깎는다(LLM을 안 부른다)
// locked: 아직 안 돌린 상태. 화면이 결제, 쿼터 안내를 그릴지 판단한다
// summary: 카드를 가로질러 읽는 한 줄(실패한 쪽은 무엇 때문이었고 성공한 쪽은 무엇이 달랐는지).
//          카드가 한 장이거나 폴백으로 채운 판에서는 null이라 화면이 그 줄을 안 그린다.
public record PickedCasesResponse(List<PickedCaseResponse> cases, String summary,
                                  String emptyReason, boolean locked) {

    public static PickedCasesResponse of(List<PickedCaseResponse> cases, String summary) {
        return new PickedCasesResponse(cases, cases.isEmpty() ? null : summary,
                cases.isEmpty() ? "NO_MATCH" : null, false);
    }

    public static PickedCasesResponse noProfile() {
        return new PickedCasesResponse(List.of(), null, "NO_PROFILE", false);
    }

    public static PickedCasesResponse notRun() {
        return new PickedCasesResponse(List.of(), null, null, true);
    }
}
