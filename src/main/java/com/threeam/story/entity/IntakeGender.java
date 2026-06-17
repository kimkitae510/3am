package com.threeam.story.entity;

// 유저 본인의 성별만 받는다. 상대 성별은 받지 않는다 — 한국어는 3인칭 대명사를 거의 안 써서
// "상대분"으로 다 굴러가고, 내 성별에서 이성이라고 추론했다가 틀리면 상담자가 상대를
// 잘못된 성별로 부르는 사고가 난다. 안 부르면 그 위험 자체가 없다.
public enum IntakeGender {
    MALE("남"),
    FEMALE("여");

    private final String caseVocabulary;

    IntakeGender(String caseVocabulary) {
        this.caseVocabulary = caseVocabulary;
    }

    // 사례 데이터가 쓰는 어휘("남", "여"). 프로필에 실을 때 이 값으로 바꾼다.
    public String caseVocabulary() {
        return caseVocabulary;
    }
}
