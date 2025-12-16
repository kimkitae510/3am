package com.threeam.payment.dto;

import com.threeam.payment.entity.PaymentItem;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;

// 결제 화면 진입 시 한 번에 내려주는 설정. clientKey가 비어 있으면(mock) 프론트는
// 위젯 없이 모의 승인 흐름을 탄다 — PG 키 없이도 전체 UX를 개발/시연할 수 있게 하기 위함.
@Getter
public class PaymentConfigResponse {

    private final String provider;
    private final String clientKey;
    private final List<ItemView> items;

    public PaymentConfigResponse(String provider, String clientKey) {
        this.provider = provider;
        this.clientKey = clientKey;
        this.items = Arrays.stream(PaymentItem.values()).map(ItemView::new).toList();
    }

    @Getter
    public static class ItemView {
        private final String code;
        private final String name;
        private final String kind;
        private final int count;
        private final int amount;

        private ItemView(PaymentItem item) {
            this.code = item.name();
            this.name = item.getDisplayName();
            this.kind = item.getKind().name();
            this.count = item.getCount();
            this.amount = item.getAmount();
        }
    }
}
