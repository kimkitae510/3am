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
    SIGNUP_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "U003", "가입 요청이 너무 많아요. 내일 다시 시도해 주세요."),
    // 코드 불일치와 "코드 발급 이력 없음"을 한 코드로 합친다 — 응답이 갈리면 발급 여부 추측에 쓰인다.
    VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "U004", "인증 코드가 올바르지 않아요. 다시 확인해 주세요."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "U005", "인증 코드가 만료됐어요. 코드를 다시 요청해 주세요."),
    VERIFICATION_RESEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "U006", "인증 메일을 방금 보냈어요. 1분 뒤에 다시 요청해 주세요."),
    VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "U007", "인증 시도가 너무 많았어요. 코드를 다시 요청해 주세요."),
    MAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "U008", "인증 메일 발송에 실패했어요. 잠시 후 다시 시도해 주세요."),

    // 인증
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "A001", "비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "유효하지 않은 토큰입니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A003", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A004", "접근 권한이 없습니다."),
    // 이메일 존재 여부가 응답으로 갈리면 계정 수집(enumeration)에 쓰인다. 로그인 실패는 이 하나로 통일.
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A005", "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "A006", "로그인 시도가 너무 많았어요. 15분 뒤에 다시 시도해 주세요."),

    // 사연
    STORY_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "사연을 찾을 수 없습니다."),

    // 진단
    ASSESSMENT_NO_MESSAGES(HttpStatus.BAD_REQUEST, "AS001", "진단할 대화 내용이 없습니다."),
    ASSESSMENT_NO_NEW_MESSAGES(HttpStatus.CONFLICT, "AS002",
            "마지막 진단 이후 새로운 대화가 없어요. 이야기를 나눈 뒤 다시 진단해 주세요."),
    ASSESSMENT_NO_NEW_FACTS(HttpStatus.CONFLICT, "AS003",
            "지난 진단과 비교해 확률을 바꿀 만한 새로운 사실이 없었어요. 어떤 일이 있었는지 들려주시면 다시 진단해 드릴게요."),

    // LLM
    LLM_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "L001", "AI 응답 생성에 실패했습니다."),

    // 사용량 제한
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Q001",
            "오늘의 무료 횟수와 이용권을 모두 썼어요. 이용권을 채우거나 내일 다시 만나요."),
    GENERATION_IN_PROGRESS(HttpStatus.TOO_MANY_REQUESTS, "Q002", "아직 이전 답변을 만드는 중이에요. 잠시만 기다려 주세요."),

    // 결제
    PAYMENT_ITEM_NOT_FOUND(HttpStatus.BAD_REQUEST, "P001", "존재하지 않는 상품입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "결제 내역을 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "P003", "결제 금액이 주문 금액과 다릅니다."),
    PAYMENT_INVALID_STATE(HttpStatus.CONFLICT, "P004", "지금 상태에서는 처리할 수 없는 결제입니다."),
    PAYMENT_ALREADY_PROCESSING(HttpStatus.TOO_MANY_REQUESTS, "P005", "결제를 처리하는 중이에요. 잠시만 기다려 주세요."),
    PAYMENT_CONFIRM_REJECTED(HttpStatus.BAD_REQUEST, "P006", "결제가 승인되지 않았어요. 다른 수단으로 다시 시도해 주세요."),
    PAYMENT_RESULT_PENDING(HttpStatus.BAD_GATEWAY, "P007",
            "결제 결과 확인이 지연되고 있어요. 잠시 후 결제 내역에서 확인해 주세요. 완료된 결제는 자동으로 반영됩니다."),
    PAYMENT_CANCEL_REJECTED(HttpStatus.BAD_GATEWAY, "P008", "환불 처리가 거절되었어요. 잠시 후 다시 시도해 주세요."),
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT, "P009", "남은 이용권이 없어 환불할 수 없어요."),
    REFUND_ACCOUNT_REQUIRED(HttpStatus.BAD_REQUEST, "P010", "가상계좌 결제는 환불받을 계좌 정보가 필요해요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
