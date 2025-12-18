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
