package com.threeam.user.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;

public enum AuthProvider {
    EMAIL, KAKAO, NAVER;

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
