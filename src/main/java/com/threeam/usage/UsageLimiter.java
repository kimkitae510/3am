package com.threeam.usage;

// LLM 호출 어뷰징 방지. 두 겹으로 막는다:
// 1) in-flight 잠금 — 사연당 동시에 1건만 생성. 연타·중복요청을 접수 단계에서 거부한다.
// 2) 일일 쿼터 — 유저·종류별 하루 호출 횟수 제한. 접수 시점에 차감한다(fire-and-forget이라
//    성공 시점 차감은 콜백 유실 시 과금이 새기 때문).
// 현재 구현은 인메모리(단일 인스턴스 전제). 운영 전환 시 이 인터페이스 뒤를 Redis 구현으로 교체한다.
public interface UsageLimiter {

    // 잠금 획득 실패(이미 생성 중) 시 GENERATION_IN_PROGRESS(429)를 던진다.
    void acquireInFlight(UsageKind kind, Long storyId);

    void releaseInFlight(UsageKind kind, Long storyId);

    // 한도 초과 시 QUOTA_EXCEEDED(429)를 던진다.
    void consumeDaily(UsageKind kind, Long userId);

    // LLM 비용이 나가기 전에 실패한 경우(소유권 없음 등)에만 차감을 되돌린다.
    void refundDaily(UsageKind kind, Long userId);
}
