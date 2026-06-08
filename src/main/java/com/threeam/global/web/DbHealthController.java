package com.threeam.global.web;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 외부 감시가 nginx와 앱을 넘어 DB까지 관통해 확인하는 창구.
// 첫 페이지는 정적 파일이라 백엔드가 죽어도 200이 나온다 — 이 경로는 DB 왕복이 성공해야 200.
// 내려주는 건 상태 한 단어뿐이라 무인증 공개해도 새는 정보가 없다.
@RestController
@RequiredArgsConstructor
public class DbHealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/api/health/db")
    public Map<String, String> db() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }
}
