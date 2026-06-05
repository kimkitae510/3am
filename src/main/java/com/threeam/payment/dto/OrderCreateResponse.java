package com.threeam.payment.dto;

import com.threeam.payment.entity.Payment;
import lombok.Getter;

// 프론트가 결제창을 띄우는 데 필요한 최소 정보. orderName은 토스 위젯의 주문명 파라미터,
// pgRef는 패들 결제창이 열 거래 식별자다 — PG마다 한쪽만 쓴다.
@Getter
public class OrderCreateResponse {

    private final String orderId;
    private final String item;
    private final String orderName;
    private final int amount;
    private final String pgRef;

    private OrderCreateResponse(String orderId, String item, String orderName, int amount, String pgRef) {
        this.orderId = orderId;
        this.item = item;
        this.orderName = orderName;
        this.amount = amount;
        this.pgRef = pgRef;
    }

    public static OrderCreateResponse from(Payment payment, String pgRef) {
        return new OrderCreateResponse(payment.getOrderId(), payment.getItem().name(),
                payment.getItem().getDisplayName(), payment.getAmount(), pgRef);
    }
}
