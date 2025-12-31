package com.threeam.user.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 같은 IP가 하루에 요청할 수 있는 인증 메일 수 제한. 우리 서버를 남의 메일함 폭탄(메일 봄빙)에
// 쓰는 걸 막는 게 목적이라 가입 한도(5)보다 여유 있게 둔다. SignupRateLimiter처럼 인메모리 느슨한 방어.
@Slf4j
@Component
public class MailSendRateLimiter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_PER_DAY = 20;

    private record Counter(LocalDate date, int count) {}

    private final ConcurrentHashMap<String, Counter> byIp = new ConcurrentHashMap<>();

    public void check(String ip) {
        LocalDate today = LocalDate.now(KST);
        Counter updated = byIp.compute(ip, (k, prev) -> {
            if (prev == null || !prev.date().equals(today)) {
                return new Counter(today, 1);
            }
            return new Counter(today, prev.count() + 1);
        });
        if (updated.count() > MAX_PER_DAY) {
            log.warn("인증 메일 IP 한도 초과 ip={} count={}", ip, updated.count());
            throw new BusinessException(ErrorCode.SIGNUP_RATE_LIMITED);
        }
    }
}
