package com.threeam.payment.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.usage.UsageKind;
import java.util.List;
import lombok.Getter;

// 판매 상품 정의. 금액은 항상 서버의 이 정의가 기준이다 — 프론트가 보내는 금액은
// 검증 대상일 뿐 절대 가격 결정에 쓰지 않는다(위변조 차단).
// 가격을 바꿔도 지난 결제는 payments.amount에 당시 금액이 박제되어 있어 영향 없다.
//
// 한 상품이 여러 종류의 이용권을 지급할 수 있다(묶음).
// 환불은 전량 미사용일 때만 전액 — 부분 환불(회당 가치 가중)은 폐지되어 회당 가치 정의가 없다.
@Getter
public enum PaymentItem {

    BUNDLE_STANDARD("대화 10회 + 진단 2회", 1200, List.of(
            new Grant(UsageKind.CHAT, 10),
            new Grant(UsageKind.ASSESSMENT, 2)));

    private final String displayName;
    private final int amount;
    private final List<Grant> grants;

    PaymentItem(String displayName, int amount, List<Grant> grants) {
        this.displayName = displayName;
        this.amount = amount;
        this.grants = grants;
    }

    public record Grant(UsageKind kind, int count) {
    }

    public static PaymentItem parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }
    }
}
