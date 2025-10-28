package com.threeam.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "입력값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "서버 오류가 발생했습니다."),

    // 회원
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "U001", "이미 사용 중인 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U002", "사용자를 찾을 수 없습니다."),

    // 인증
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "A001", "비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "유효하지 않은 토큰입니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A003", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A004", "접근 권한이 없습니다."),

    // 사연
    STORY_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "사연을 찾을 수 없습니다."),

    // 진단
    ASSESSMENT_NO_MESSAGES(HttpStatus.BAD_REQUEST, "AS001", "진단할 대화 내용이 없습니다."),

    // LLM
    LLM_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "L001", "AI 응답 생성에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
