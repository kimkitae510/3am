package com.threeam.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

// 결제위젯 successUrl로 돌아온 값 그대로. amount는 위변조 검증용일 뿐 금액 결정에 쓰지 않는다.
@Getter
public class ConfirmRequest {

    @NotBlank(message = "paymentKey는 필수입니다.")
    private String paymentKey;

    @NotBlank(message = "orderId는 필수입니다.")
    private String orderId;

    @NotNull(message = "amount는 필수입니다.")
    @Positive(message = "amount는 양수여야 합니다.")
    private Integer amount;
}
