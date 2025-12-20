package com.threeam.global.web;

import jakarta.servlet.http.HttpServletRequest;

// 프록시/로드밸런서 뒤에서도 실제 클라이언트 IP를 얻는다. X-Forwarded-For의 첫 항목이 원 IP.
// 주의: XFF는 위조 가능하므로 신뢰 경계(믿을 수 있는 프록시)가 앞단에 있을 때만 의미가 있다.
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
