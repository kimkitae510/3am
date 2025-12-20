package com.threeam.security.jwt;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 로그아웃/비밀번호 변경/탈퇴 시 그 유저의 기존 access token을 즉시 무효화한다.
// JWT는 stateless라 발급된 토큰을 회수할 수 없어서, "이 시각 이전에 발급된 토큰은 무효" 기준선을 유저별로 들고
// 필터에서 대조한다. 인메모리다(단일 인스턴스 전제). 다중 인스턴스면 공유 저장소(Redis 등)로 옮겨야 한다.
// 항목은 access token 만료 시간이 지나면 의미가 없어지므로, 그 이후 접근 시 청소한다.
@Component
public class TokenInvalidationRegistry {

    private final ConcurrentHashMap<Long, Long> invalidBefore = new ConcurrentHashMap<>();

    // iat(초 단위)와의 경계 오차를 없애려고 1초 여유를 둔다(같은 초에 발급된 토큰도 확실히 무효화).
    public void invalidateAll(Long userId) {
        invalidBefore.put(userId, System.currentTimeMillis() + 1000);
    }

    public boolean isInvalid(Long userId, long issuedAtMillis) {
        Long boundary = invalidBefore.get(userId);
        return boundary != null && issuedAtMillis < boundary;
    }

    // 기준선보다 뒤에 발급된 토큰만 도는 상태가 되면 항목은 더 이상 필요 없다.
    public void evictIfExpired(Long userId, long nowMillis, long accessValidityMillis) {
        Long boundary = invalidBefore.get(userId);
        if (boundary != null && boundary + accessValidityMillis < nowMillis) {
            invalidBefore.remove(userId, boundary);
        }
    }
}
