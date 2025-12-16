package com.threeam.payment.client;

// PG가 알려주는 결제의 실제 상태. 벤더 문자열을 서비스 계층에 흘리지 않기 위한 공용 어휘.
public enum PgStatus {

    READY,                 // PG에 주문만 있고 승인 시도 전
    IN_PROGRESS,           // PG 쪽에서 처리 중
    WAITING_FOR_DEPOSIT,   // 가상계좌 입금 대기
    DONE,                  // 승인 완료
    CANCELED,              // 전액 취소
    PARTIAL_CANCELED,      // 부분 취소(미사용분 비례 환불이 이 경우)
    FAILED,                // 명확한 거절(재시도해도 같은 결과)
    EXPIRED,               // 만료(미승인 방치, 입금 기한 초과)
    NOT_FOUND,             // PG에 이 주문이 없음(위젯까지 못 간 이탈)
    UNKNOWN                // 해석 불가 응답 — 아무것도 바꾸지 말 것
}
