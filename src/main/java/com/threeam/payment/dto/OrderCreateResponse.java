package com.threeam.payment.dto;

import com.threeam.payment.entity.Payment;
import lombok.Getter;

// 프론트가 결제위젯을 띄우는 데 필요한 최소 정보. orderName은 위젯의 주문명 파라미터로 쓰인다.
@Getter
public class OrderCreateResponse {

    private final String orderId;
    private final String item;
    private final String orderName;
    private final int amount;

    private OrderCreateResponse(String orderId, String item, String orderName, int amount) {
        this.orderId = orderId;
        this.item = item;
        this.orderName = orderName;
        this.amount = amount;
    }

    public static OrderCreateResponse from(Payment payment) {
        return new OrderCreateResponse(payment.getOrderId(), payment.getItem().name(),
                payment.getItem().getDisplayName(), payment.getAmount());
    }
}
