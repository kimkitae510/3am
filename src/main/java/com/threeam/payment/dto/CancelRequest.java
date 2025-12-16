package com.threeam.payment.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class CancelRequest {

    @Size(max = 200, message = "취소 사유는 200자 이하여야 합니다.")
    private String reason;

    // 가상계좌로 입금한 결제의 환불에만 필요. 카드, 간편결제는 결제 수단으로 자동 환불된다.
    private String refundBank;
    private String refundAccount;
    private String refundHolder;
}
