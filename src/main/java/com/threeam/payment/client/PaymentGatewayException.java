package com.threeam.payment.client;

// PG 호출 결과 불명(5xx, 파싱 불능). BusinessException이 아닌 이유:
// 이건 최종 응답이 아니라 "상태를 확정하지 말라"는 내부 신호다. 서비스 계층이
// 상태를 보존한 채 PAYMENT_RESULT_PENDING으로 번역해 내보낸다.
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
