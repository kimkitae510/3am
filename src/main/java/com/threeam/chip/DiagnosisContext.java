package com.threeam.chip;

// 이번 상담 호출에 진단 데이터를 얼마나 실을지. 모듈 단위로 정한다(칩 하나하나가 아니라).
//
// 기본이 NONE인 이유: 확률이 한 번 30%로 나오면 매 턴 그 숫자가 너무 강한 기준점이 된다.
// "지금 연락해도 될까요"처럼 새로 판단해야 하는 질문까지 기존 결론을 어떻게 유지해 설명할까로
// 흘러, 상담이 아니라 진단 결과 해설이 된다. 그렇다고 버리기엔 관계 심리와 재발 위험, 관찰
// 지점은 특정 질문에서 값이 크다. 그래서 저장은 다 하고 필요한 자리에서만 꺼낸다.
//
// 채팅과 진단의 정합성은 숫자를 맞춰서가 아니라 같은 판단 원칙(감정과 선택의 구분, 수동 반응보다
// 능동 행동)을 공유해서 맞춘다 — "진단이 30%였으니 이번 답도 30에 맞춰라"는 순환논리다.
public enum DiagnosisContext {

    // 안 싣는다. 사연과 대화, 최신 사건만으로 상담한다.
    NONE,

    // 요인 판정과 지켜볼 것. 무엇이 바뀌면 판단이 바뀌는지를 다루는 자리라 확률 숫자는 뺀다.
    FACTORS_WATCH,

    // 관계 심리와 재발 위험. 재회 성사가 아니라 재회 뒤 관계를 다루는 자리다.
    RELATIONSHIP,

    // 전부. 진단 결과 자체를 설명하는 자리와, 자유입력에서 유저가 진단을 직접 물은 경우.
    FULL;

    public boolean loadsProbability() {
        return this == FULL;
    }

    public boolean loadsFactors() {
        return this == FULL || this == FACTORS_WATCH;
    }

    public boolean loadsWatchPoints() {
        return this == FULL || this == FACTORS_WATCH;
    }

    public boolean loadsPsychology() {
        return this == FULL || this == RELATIONSHIP;
    }

    public boolean loadsRelapseRisk() {
        return this == FULL || this == RELATIONSHIP;
    }
}
