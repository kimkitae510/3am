package com.threeam.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class OrderCreateRequest {

    // 상품 코드(PaymentItem enum 이름). 금액은 받지 않는다 — 서버가 정의로 확정한다.
    @NotBlank(message = "상품 코드는 필수입니다.")
    private String item;
}
