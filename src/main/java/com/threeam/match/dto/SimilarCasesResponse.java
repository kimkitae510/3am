package com.threeam.match.dto;

import java.util.List;

// cases가 비면 이유를 함께 내려준다 — 화면이 "없음"과 "아직 못 찾음"을 다르게 말해야 한다.
// NO_PROFILE: 분석을 아직 안 했거나 대화에서 이별 사유가 안 드러남(대화를 더 하면 열린다)
// NO_MATCH: 프로필은 있는데 닮은 사례가 임계에 못 미침(데이터가 더 쌓여야 한다)
public record SimilarCasesResponse(List<SimilarCaseResponse> cases, String emptyReason) {

    public static SimilarCasesResponse of(List<SimilarCaseResponse> cases) {
        return new SimilarCasesResponse(cases, cases.isEmpty() ? "NO_MATCH" : null);
    }

    public static SimilarCasesResponse noProfile() {
        return new SimilarCasesResponse(List.of(), "NO_PROFILE");
    }
}
