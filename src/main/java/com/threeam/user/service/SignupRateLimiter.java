package com.threeam.user.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
        check(ip, ErrorCode.SIGNUP_RATE_LIMITED);
    }

    // 호출한 쪽의 맥락에 맞는 문구로 거절한다 — 둘러보기 시작도 계정 생성이라 같은 카운터를
    // 쓰지만, 가입한 적 없는 사람에게 "가입 요청이 많다"고 말하면 뜻이 통하지 않는다.
    public void check(String ip, ErrorCode errorCode) {
        LocalDate today = LocalDate.now(KST);
        Counter updated = byIp.compute(ip, (k, prev) -> {
            if (prev == null || !prev.date().equals(today)) {
                return new Counter(today, 1);
            }
            return new Counter(today, prev.count() + 1);
        });
        if (updated.count() > MAX_PER_DAY) {
            log.warn("가입 IP 한도 초과 ip={} count={}", ip, updated.count());
            throw new BusinessException(errorCode);
        }
    }

    // 지난 날짜 항목은 다시 접근될 때만 리셋되므로, 한 번 오고 안 오는 IP는 영영 남는다.
    // 방문자 수만큼 맵이 단조 증가해 사실상 누수라 하루 한 번 쓸어낸다.
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void evictStale() {
        LocalDate today = LocalDate.now(KST);
        int before = byIp.size();
        byIp.values().removeIf(c -> !c.date().equals(today));
        log.info("가입 IP 카운터 정리 {} -> {}", before, byIp.size());
    }
}
