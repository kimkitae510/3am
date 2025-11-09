package com.threeam.usage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApiRateLimitInterceptorTest {

    @Mock
    private RateLimiter rateLimiter;

    private final UsageProperties properties = new UsageProperties();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ApiRateLimitInterceptor interceptor() {
        return new ApiRateLimitInterceptor(rateLimiter, properties);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("1.2.3.4");
        return request;
    }

    @Test
    @DisplayName("로그인은 인증 전이므로 IP 기준으로 login 규칙을 탄다")
    void login_byIp() {
        interceptor().preHandle(request("POST", "/api/auth/login"), new MockHttpServletResponse(), null);

        verify(rateLimiter).check(eq("login"), eq("ip:1.2.3.4"), eq(5), anyInt());
    }

    @Test
    @DisplayName("인증된 GET 조회는 유저 기준으로 read 규칙을 탄다")
    void read_byUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, java.util.List.of()));

        interceptor().preHandle(request("GET", "/api/stories/10/messages/since"),
                new MockHttpServletResponse(), null);

        verify(rateLimiter).check(eq("read"), eq("u:7"), eq(120), anyInt());
    }

    @Test
    @DisplayName("사연 삭제는 write 규칙을 탄다")
    void delete_write() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, java.util.List.of()));

        interceptor().preHandle(request("DELETE", "/api/stories/10"), new MockHttpServletResponse(), null);

        verify(rateLimiter).check(eq("write"), eq("u:7"), eq(20), anyInt());
    }

    @Test
    @DisplayName("LLM 엔드포인트(메시지 전송·진단)는 이 인터셉터의 규칙을 타지 않는다(일일 쿼터로 별도 관리)")
    void llmEndpoints_notMatched() {
        interceptor().preHandle(request("POST", "/api/stories/10/messages"),
                new MockHttpServletResponse(), null);
        interceptor().preHandle(request("POST", "/api/stories/10/assessments"),
                new MockHttpServletResponse(), null);

        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("첫 매칭 규칙만 적용된다 — 삭제가 read 규칙까지 이중으로 타지 않는다")
    void firstMatchOnly() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, java.util.List.of()));

        interceptor().preHandle(request("DELETE", "/api/stories/10"), new MockHttpServletResponse(), null);

        verify(rateLimiter, never()).check(eq("read"), ArgumentMatchers.any(), anyInt(), anyInt());
    }
}
