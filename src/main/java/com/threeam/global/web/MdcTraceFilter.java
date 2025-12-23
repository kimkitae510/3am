package com.threeam.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 요청마다 상관관계 ID(traceId)를 MDC에 심는다. 로그 패턴에 %X{traceId}로 찍히면
// "이 유저의 이 시각 요청"에서 벌어진 로그들을 한 줄기로 묶어 CS 추적이 된다.
// 주의: LLM 호출은 별도 스레드(sendAsync)에서 돌아 MDC가 자동 전파되지 않는다 —
// 요청 스레드의 로그(접수, 저장, 컨트롤러)까지가 이 traceId로 묶이는 범위다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcTraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming
                : UUID.randomUUID().toString().substring(0, 8);
        MDC.put(TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
