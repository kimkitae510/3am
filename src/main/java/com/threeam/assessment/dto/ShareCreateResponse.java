package com.threeam.assessment.dto;

import lombok.Getter;

// 공유 토큰 발급 응답. 링크 조립(origin + /s/{token})은 프론트 몫 — 서버는 열쇠만 준다.
@Getter
public class ShareCreateResponse {

    private final String token;

    public ShareCreateResponse(String token) {
        this.token = token;
    }
}
