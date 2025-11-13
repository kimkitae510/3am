package com.threeam.usage;

// LLM 호출 어뷰징 방지. 두 겹으로 막는다:
// 1) in-flight 잠금 — 사연당 동시에 1건만 생성. 연타·중복요청을 접수 단계에서 거부한다.
// 2) 일일 쿼터 — 유저·종류별 하루 호출 횟수 제한. 후차감: 접수 시점엔 검사만 하고,
//    LLM이 정상 답을 만들어 저장까지 성공했을 때만 1회 기록한다.
//    LLM 장애(폴백)에 유저 쿼터가 깎이지 않게 하기 위한 결정 — 실패는 유저 잘못이 아니다.
//    검사와 기록 사이의 틈으로 동시 요청이 한도를 순간 초과할 수 있으나(사연 수만큼 유계),
//    in-flight 잠금이 사연당 1건으로 조여 실제 초과 폭은 미미하다고 보고 수용한다.
public interface UsageLimiter {

    // 잠금 획득 실패(이미 생성 중) 시 GENERATION_IN_PROGRESS(429)를 던진다.
    void acquireInFlight(UsageKind kind, Long storyId);

    void releaseInFlight(UsageKind kind, Long storyId);

    // 접수 관문: 오늘 한도를 이미 다 썼으면 QUOTA_EXCEEDED(429)를 던진다. 차감하지 않는다.
    void checkDaily(UsageKind kind, Long userId);

    // 성공 시 호출: 오늘 사용량 1회 기록. 자정이 지나 있었다면 1부터 다시 센다.
    void recordDaily(UsageKind kind, Long userId);
}
