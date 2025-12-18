package com.threeam.payment.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.usage.Entitlement;
import com.threeam.usage.UsageKind;
import java.util.Collection;
import java.util.List;
import lombok.Getter;

// 판매 상품 정의. 금액은 항상 서버의 이 정의가 기준이다 — 프론트가 보내는 금액은
// 검증 대상일 뿐 절대 가격 결정에 쓰지 않는다(위변조 차단).
// 가격을 바꿔도 지난 결제는 payments.amount에 당시 금액이 박제되어 있어 영향 없다.
//
// 한 상품이 여러 종류의 이용권을 지급할 수 있다(묶음). unitValue는 환불 가중치로,
// "남은 횟수 x 회당 가치"로 미사용분을 계산한다 — 진단 1회와 대화 1회의 가치가 달라
// 단순 횟수 비례로는 환불액이 왜곡되기 때문. 각 상품의 unitValue 합은 amount와 같아야 한다.
@Getter
public enum PaymentItem {

    // 대화 20회(회당 20원) + 진단 3회(회당 500원) = 1,900원
    BUNDLE_STANDARD("대화 20회 + 진단 3회", 1900, List.of(
            new Grant(UsageKind.CHAT, 20, 20),
            new Grant(UsageKind.ASSESSMENT, 3, 500)));

    private final String displayName;
    private final int amount;
    private final List<Grant> grants;

    PaymentItem(String displayName, int amount, List<Grant> grants) {
        int valueSum = grants.stream().mapToInt(g -> g.count() * g.unitValue()).sum();
        if (valueSum != amount) {
            // 정의 실수를 부팅 시점에 잡는다 — 환불액 합계가 결제액과 어긋나면 안 된다.
            throw new IllegalStateException(name() + " 이용권 가치 합(" + valueSum + ")이 금액(" + amount + ")과 다릅니다");
        }
        this.displayName = displayName;
        this.amount = amount;
        this.grants = grants;
    }

    public record Grant(UsageKind kind, int count, int unitValue) {
    }

    // 미사용분 환불액 = 각 이용권의 남은 횟수 x 회당 가치. 전량 미사용이면 정확히 amount가 된다.
    public int refundableAmount(Collection<Entitlement> entitlements) {
        int refundable = entitlements.stream()
                .mapToInt(e -> unitValueOf(e.getKind()) * e.remainingCount())
                .sum();
        return Math.min(refundable, amount);
    }

    private int unitValueOf(UsageKind kind) {
        return grants.stream()
                .filter(grant -> grant.kind() == kind)
                .mapToInt(Grant::unitValue)
                .findFirst()
                .orElse(0);
    }

    public static PaymentItem parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }
    }
}
