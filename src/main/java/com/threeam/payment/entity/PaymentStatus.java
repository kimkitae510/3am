package com.threeam.payment.entity;

// 결제 한 건의 생애 상태. 돈이 걸린 기록이라 어떤 상태도 행을 지우지 않고 전이만 한다.
//
// READY ─┬→ IN_PROGRESS ─┬→ DONE ─→ CANCEL_REQUESTED ─┬→ CANCELED
//        │               ├→ WAITING_FOR_DEPOSIT ──────┤   (거절 시 DONE 복귀)
//        │               ├→ FAILED                    │
//        │               └→ EXPIRED                   │
//        └→ EXPIRED (미시도 방치)                       │
//   WAITING_FOR_DEPOSIT ─┬→ DONE (입금)               │
//                        └→ EXPIRED (기한 초과)        │
//
// IN_PROGRESS는 "승인 API를 쐈는데 결과를 모르는" 구간을 포함한다 — 여기 오래 머문 건은
// 재동기화 스케줄러가 PG 조회로 실제 결과를 확정한다(돈은 나갔는데 지급이 없는 상태 방지).
public enum PaymentStatus {

    READY,                 // 주문 생성됨. 아직 돈이 움직이지 않음
    IN_PROGRESS,           // 승인 진행 중(응답 불명 포함) — 재동기화 대상
    WAITING_FOR_DEPOSIT,   // 가상계좌 발급됨. 입금 대기 — 웹훅/재동기화로 확정
    DONE,                  // 승인 완료. 이용권 지급됨
    FAILED,                // 승인 거절(사유는 failReason)
    EXPIRED,               // 미시도 방치 또는 입금 기한 초과
    CANCEL_REQUESTED,      // 취소 API 호출 중(응답 불명 포함) — 재동기화 대상
    CANCELED;              // 취소(환불) 완료. 이용권 회수됨

    // 여기서 끝난 결제는 어떤 이벤트로도 되살리지 않는다(웹훅 재전송, 재동기화가 와도 무시).
    public boolean isTerminal() {
        return this == FAILED || this == EXPIRED || this == CANCELED;
    }
}
