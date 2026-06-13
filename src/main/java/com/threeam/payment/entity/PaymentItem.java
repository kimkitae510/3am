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

    // 단일 상품으로 시작한다 — 등급을 여럿 두면 유저는 고민하고 우리는 무엇이 팔릴지 모른다.
    // 소진 속도와 재구매율을 보고 늘린다. 구성 근거(2026-05 실비): 채팅 1턴 48.7원,
    // 분석 1회 82~122원(사연 복잡도에 따라 추론량이 3배까지 뛴다). 원가 약 370원.
    // 재분석 가드가 새 대화나 새 사실을 요구해서 분석을 여러 번 보려면 채팅이 먼저 필요하다 —
    // 그래서 분석보다 채팅을 여러 회 준다.
    BUNDLE_STANDARD("대화 5회 + 분석 1회", 2900, List.of(
            new Grant(UsageKind.CHAT, 5),
            new Grant(UsageKind.ASSESSMENT, 1)));

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
