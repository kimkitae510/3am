package com.threeam.usage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

// 일반 API의 분당 빈도 제한. 위에서부터 첫 매칭 규칙만 적용한다.
// LLM 엔드포인트(메시지 전송·진단)는 일일 쿼터 + in-flight 잠금(UsageLimiter)으로 따로 관리하므로
// 여기 규칙에 넣지 않는다 — 패턴이 겹치지 않게 유지할 것.
@Component
@RequiredArgsConstructor
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    private static final int WINDOW_SECONDS = 60;

    private final RateLimiter rateLimiter;
    private final UsageProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private record Rule(String method, String pattern, String name, IntSupplier limit) {}

    private List<Rule> rules() {
        UsageProperties.Api api = properties.getApi();
        return List.of(
                new Rule("POST", "/api/auth/login", "login", api::getLoginPerMinute),
                new Rule("POST", "/api/users/signup", "signup", api::getSignupPerMinute),
                new Rule("POST", "/api/auth/reissue", "reissue", api::getReissuePerMinute),
                new Rule("POST", "/api/auth/logout", "write", api::getWritePerMinute),
                new Rule("POST", "/api/stories", "write", api::getWritePerMinute),
                new Rule("DELETE", "/api/stories/*", "write", api::getWritePerMinute),
                new Rule("GET", "/api/**", "read", api::getReadPerMinute));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        for (Rule rule : rules()) {
            if (rule.method().equals(request.getMethod())
                    && pathMatcher.match(rule.pattern(), request.getRequestURI())) {
                rateLimiter.check(rule.name(), subject(request), rule.limit().getAsInt(), WINDOW_SECONDS);
                break;
            }
        }
        return true;
    }

    // 인증됐으면 유저 기준(같은 유저는 IP를 바꿔도 하나로 묶임), 아니면 IP 기준.
    private String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return "u:" + userId;
        }
        // 리버스 프록시 뒤에 배포하면 X-Forwarded-For를 신뢰 목록과 함께 처리하도록 바꿔야 한다.
        return "ip:" + request.getRemoteAddr();
    }
}
