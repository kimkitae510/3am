package com.threeam.payment.service;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 웹훅은 인증 없이 열린 경로라, 같은 orderId로 반복 POST하면 그때마다 PG 재조회가 나가 비용, 부하가 된다.
// 같은 주문의 웹훅은 짧은 쿨다운 안에서 한 번만 실제 처리로 넘긴다 — 실제 토스 웹훅은 이보다 드물게 오고,
// 혹 진짜 이벤트를 흘려도 재동기화 스케줄러가 뒤를 받친다. 인메모리(단일 인스턴스 전제).
@Component
public class WebhookRateLimiter {

    private static final long COOLDOWN_MILLIS = 5000;

    private final ConcurrentHashMap<String, Long> lastHandledAt = new ConcurrentHashMap<>();

    public boolean allow(String orderId) {
        long now = System.currentTimeMillis();
        // compute가 원자적이라, 동시에 들어와도 한 요청만 통과한다. 타임스탬프 동일(같은 ms) 오판을 피하려 플래그로 판정.
        boolean[] allowed = {false};
        lastHandledAt.compute(orderId, (k, prev) -> {
            if (prev == null || now - prev >= COOLDOWN_MILLIS) {
                allowed[0] = true;
                return now;
            }
            return prev;
        });
        return allowed[0];
    }
}
