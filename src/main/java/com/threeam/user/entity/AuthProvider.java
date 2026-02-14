package com.threeam.user.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;

public enum AuthProvider {
    // GUEST는 로그인 없이 시작한 체험 계정 — 이메일/소셜 연결로 승격되면 provider가 교체된다.
    EMAIL, KAKAO, NAVER, GUEST;

    // OAuth 경로 변수용 — EMAIL은 소셜 로그인 대상이 아니므로 여기서 받지 않는다.
    public static AuthProvider fromOAuthPath(String path) {
        if ("kakao".equalsIgnoreCase(path)) {
            return KAKAO;
        }
        if ("naver".equalsIgnoreCase(path)) {
            return NAVER;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
    }
}
