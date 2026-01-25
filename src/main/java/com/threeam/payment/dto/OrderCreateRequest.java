package com.threeam.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class OrderCreateRequest {

    // 상품 코드(PaymentItem enum 이름). 금액은 받지 않는다 — 서버가 정의로 확정한다.
    @NotBlank(message = "상품 코드는 필수입니다.")
    private String item;

    // 디지털 콘텐츠 청약철회 제한 고지에 대한 동의. 전자상거래법 제17조가 "고지 + 동의"를
    // 제한의 성립 요건으로 두므로 주문 생성의 필수 관문이다.
    private boolean refundPolicyAgreed;
}
