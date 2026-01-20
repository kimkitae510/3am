package com.threeam.usage;

// LLM 호출 어뷰징 방지. 두 겹으로 막는다:
// 1) 생성 락 — 유저+종류당 동시에 1건만 생성. 연타, 중복요청, 여러 사연 동시 발사를 접수 단계에서 거부한다.
//    DB 분산 락(GenerationLock)이라 재시작, 멀티인스턴스에서도 유효하다. 유저 단위라
//    "검사 통과 → 기록" 사이에 다른 요청이 끼어들 수 없어 후차감 한도 초과(TOCTOU)도 함께 막힌다.
// 2) 일일 쿼터 — 유저, 종류별 하루 호출 횟수 제한. 후차감: 접수 시점엔 검사만 하고,
//    LLM이 정상 답을 만들어 저장까지 성공했을 때만 1회 기록한다.
//    LLM 장애(폴백)에 유저 쿼터가 깎이지 않게 하기 위한 결정 — 실패는 유저 잘못이 아니다.
public interface UsageLimiter {

    // 잠금 획득 실패(이미 생성 중) 시 GENERATION_IN_PROGRESS(429)를 던진다. 유저+종류 단위.
    void acquireInFlight(UsageKind kind, Long userId);

    void releaseInFlight(UsageKind kind, Long userId);

    // 접수 관문: 오늘 한도를 이미 다 썼으면 QUOTA_EXCEEDED(429)를 던진다. 차감하지 않는다.
    void checkDaily(UsageKind kind, Long userId);

    // 성공 시 호출: 오늘 사용량 1회 기록. 자정이 지나 있었다면 1부터 다시 센다.
    // 무료 한도가 이미 찼으면 결제로 산 이용권에서 1회 차감한다(무료 우선 소진).
    void recordDaily(UsageKind kind, Long userId);

    // 오늘 남은 무료 횟수(0 이상). 화면에 "오늘 N회 남음"을 보여주기 위한 조회 전용.
    int remainingDaily(UsageKind kind, Long userId);

    // 오늘 적용되는 무료 한도. 유저별로 다를 수 있다(예: 대화는 가입 당일만 상향).
    int dailyLimit(UsageKind kind, Long userId);

    // 결제로 산 이용권의 잔여 횟수 합(환불된 것 제외). 무료 한도와 별개로 표시된다.
    int paidRemaining(UsageKind kind, Long userId);
}
