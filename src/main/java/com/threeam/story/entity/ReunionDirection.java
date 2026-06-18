package com.threeam.story.entity;

// 채팅이 스스로 낸 재회 방향 판단. 정밀 진단의 확률과는 다른 층이다 —
// 진단은 유형과 요인으로 확률을 계산하고, 이건 지금까지 대화에서 확인된 상대의 행동과 선택만 본다.
// 그래서 숫자로 번역하지 않는다(POSITIVE가 몇 % 이상이라는 뜻이 아니다).
//
// turn-2 답변 끝의 ---chat-meta--- 에서 뽑아 사연에 저장한다. 진단을 안 받은 사연에서는
// 이것이 방향에 대해 서버가 가진 유일한 값이다.
public enum ReunionDirection {
    POSITIVE,
    UNCERTAIN,
    NEGATIVE
}
