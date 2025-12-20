package com.threeam.auth.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 로그인 brute force 완화. 이메일+IP 조합으로 연속 실패를 세고, 임계치를 넘으면 잠깐 잠근다.
// 인메모리다(단일 인스턴스 전제 + 휘발 상태). 다중 인스턴스로 가면 Redis 등 공유 저장소로 옮겨야 한다.
@Slf4j
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;

    private record Attempt(int failures, long lockedUntil) {}

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void assertNotLocked(String email, String ip) {
        Attempt attempt = attempts.get(key(email, ip));
        if (attempt != null && attempt.lockedUntil() > System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.LOGIN_LOCKED);
        }
    }

    public void recordFailure(String email, String ip) {
        long now = System.currentTimeMillis();
        attempts.compute(key(email, ip), (k, prev) -> {
            // 잠금이 이미 풀린 뒤의 실패는 카운터를 새로 시작한다.
            int base = (prev == null || prev.lockedUntil() <= now) ? 0 : prev.failures();
            int failures = base + 1;
            long lockedUntil = failures >= MAX_FAILURES ? now + LOCK_MILLIS : 0;
            if (lockedUntil > 0) {
                log.warn("로그인 연속 실패로 잠금 email={} ip={}", email, ip);
            }
            return new Attempt(failures, lockedUntil);
        });
    }

    public void recordSuccess(String email, String ip) {
        attempts.remove(key(email, ip));
    }

    private String key(String email, String ip) {
        return email + "|" + ip;
    }
}
