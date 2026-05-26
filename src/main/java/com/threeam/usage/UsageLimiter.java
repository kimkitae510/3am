package com.threeam.usage;

// LLM 호출 어뷰징 방지. 두 겹으로 막는다:
// 1) 생성 락 — 유저+종류당 동시에 1건만 생성. 연타, 중복요청, 여러 사연 동시 발사를 접수 단계에서 거부한다.
//    DB 분산 락(GenerationLock)이라 재시작, 멀티인스턴스에서도 유효하다. 유저 단위라
//    "검사 통과 → 기록" 사이에 다른 요청이 끼어들 수 없어 후차감 초과(TOCTOU)도 함께 막힌다.
// 2) 이용권 잔여 — 유저, 종류별 남은 회수. 후차감: 접수 시점엔 검사만 하고,
//    LLM이 정상 답을 만들어 저장까지 성공했을 때만 차감한다.
//    LLM 장애(폴백)에 유저 회수가 깎이지 않게 하기 위한 결정 — 실패는 유저 잘못이 아니다.
public interface UsageLimiter {

    // 잠금 획득 실패(이미 생성 중) 시 GENERATION_IN_PROGRESS(429)를 던진다. 유저+종류 단위.
    void acquireInFlight(UsageKind kind, Long userId);

    void releaseInFlight(UsageKind kind, Long userId);

    // 지금 이 유저의 생성이 실제로 돌고 있는지. 콜백은 성공이든 실패든 저장을 마친 뒤에 락을 풀므로,
    // 락이 없는데 답도 없다면 그 턴은 서버가 죽어 사라진 것이다(화면의 "..."를 끝낼 근거).
    boolean isGenerating(UsageKind kind, Long userId);

    // 접수 관문: 이용권 잔여가 units에 못 미치면 던진다(회원 QUOTA_EXCEEDED, 게스트 GUEST_LINK_REQUIRED).
    // 차감하지 않는다. units는 이 요청이 소모할 회수 — 긴 메시지는 길이에 비례해 여러 회다(호출부가 환산).
    void check(UsageKind kind, Long userId, int units);

    // 성공 시 호출: 이용권에서 units회 차감한다. 오래된 이용권부터 쓴다.
    void record(UsageKind kind, Long userId, int units);

    // 남은 회수(0 이상). 화면 표시용 조회.
    int remaining(UsageKind kind, Long userId);
}
