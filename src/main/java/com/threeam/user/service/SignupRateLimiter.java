package com.threeam.user.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 같은 IP에서 하루에 만들 수 있는 계정 수를 제한한다. 계정 무한 생성으로 무료 쿼터를 우회하는 어뷰징 완화.
// 인메모리 + 하루 경계라 재시작하면 카운터가 초기화된다(느슨한 방어). 강한 차단이 필요하면
// 이메일 인증이나 인프라 층(WAF, nginx)의 IP 제한과 병행해야 한다.
@Slf4j
@Component
public class SignupRateLimiter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_PER_DAY = 5;

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
            log.warn("가입 IP 한도 초과 ip={} count={}", ip, updated.count());
            throw new BusinessException(ErrorCode.SIGNUP_RATE_LIMITED);
        }
    }
}
