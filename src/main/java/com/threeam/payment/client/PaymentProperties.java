package com.threeam.payment.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    private String provider = "mock";

    private Toss toss = new Toss();

    // READY로 방치된 주문을 만료 처리하기까지의 시간. 위젯을 열어두고 고민하는 시간을 넉넉히 잡는다.
    private int orderExpireMinutes = 30;

    // 승인/취소 응답 불명(IN_PROGRESS, CANCEL_REQUESTED) 상태를 재동기화하기까지의 대기.
    // 너무 짧으면 정상 진행 중인 승인과 경합하고, 너무 길면 유저가 "돈 나갔는데 지급이 없다"를 오래 본다.
    private int syncAfterMinutes = 2;

    // 유저당 미결(READY) 주문 상한. 결제까지 안 가는 주문 생성 도배로 테이블이 부푸는 것을 막는다.
    // 정상 유저는 위젯을 띄우면 바로 결제하거나 30분 뒤 만료되므로 이 수에 닿을 일이 없다.
    private int maxPendingOrdersPerUser = 5;

    @Getter
    @Setter
    public static class Toss {
        // client-key는 프론트 결제위젯용(공개 가능), secret-key는 서버 승인/취소용(절대 비공개).
        private String clientKey = "";
        private String secretKey = "";
        private String baseUrl = "https://api.tosspayments.com/v1";
        private long timeoutSeconds = 30;
    }
}
