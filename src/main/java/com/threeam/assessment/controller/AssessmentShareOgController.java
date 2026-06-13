package com.threeam.assessment.controller;

import com.threeam.assessment.service.AssessmentShareService;
import com.threeam.global.exception.custom.BusinessException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 공유 링크의 진입 문. 카톡/페북 크롤러는 JS를 안 돌려서 SPA로는 미리보기 카드가 비어 나온다 —
// 이 경로만 서버가 OG 태그가 박힌 HTML을 직접 내려주고, 사람은 안의 스크립트가 SPA 화면
// (/shared/{token})으로 보낸다. 운영에선 nginx가 /s/ 를 여기로 프록시한다(개발은 SPA 라우트가 받음).
@RestController
public class AssessmentShareOgController {

    // base64url 토큰만 통과 — 경로 조각이 HTML에 실리므로 문자 집합으로 이스케이프를 대신한다.
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private final AssessmentShareService shareService;
    private final String baseUrl;

    public AssessmentShareOgController(AssessmentShareService shareService,
                                       @Value("${share.base-url}") String baseUrl) {
        this.shareService = shareService;
        this.baseUrl = baseUrl;
    }

    @GetMapping(value = "/s/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String token) {
        if (!TOKEN.matcher(token).matches()) {
            return redirectHome();
        }
        Integer probability;
        try {
            probability = shareService.getShared(token).getProbability();
        } catch (BusinessException e) {
            // 죽은 링크(삭제, 오타)는 크롤러에게도 사람에게도 첫 화면이 답이다
            return redirectHome();
        }
        if (probability == null) {
            return redirectHome();
        }
        return """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <title>재회 가능성 %d%% — 새벽 세시</title>
                <meta property="og:type" content="website">
                <meta property="og:title" content="재회 가능성 %d%%">
                <meta property="og:description" content="새벽 세시가 대화를 읽고 계산한 재회 가능성입니다. 어떤 신호가 확률을 움직였는지 함께 보여드려요.">
                <meta property="og:image" content="%s/og-share.png">
                <meta property="og:url" content="%s/s/%s">
                <meta name="twitter:card" content="summary_large_image">
                <script>location.replace('/shared/%s')</script>
                </head><body></body></html>
                """.formatted(probability, probability, baseUrl, baseUrl, token, token);
    }

    private String redirectHome() {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<script>location.replace('/')</script></head><body></body></html>";
    }
}
