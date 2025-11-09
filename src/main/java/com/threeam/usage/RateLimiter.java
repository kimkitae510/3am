package com.threeam.usage;

// 짧은 시간창 기준의 요청 빈도 제한. LLM 쿼터(UsageLimiter)와 달리
// 로그인 무차별 시도·폴링 폭주 같은 "잦은 호출" 자체를 막는 용도다.
// 현재 구현은 인메모리(단일 인스턴스 전제). 운영 전환 시 Redis 구현으로 교체한다.
public interface RateLimiter {

    // rule: 규칙 이름(카운터 분리 단위), subject: 유저 또는 IP.
    // 창 안에서 limit를 넘으면 RATE_LIMITED(429)를 던진다.
    void check(String rule, String subject, int limit, int windowSeconds);
}
