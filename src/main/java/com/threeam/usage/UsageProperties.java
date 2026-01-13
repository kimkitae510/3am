package com.threeam.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 사용량 제한 설정. 한도 수치는 실제 비용 추이를 보고 조정한다.
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    // 첫날 한도 상향(가입 당일 10회) 방식은 폐지 — 못 쓰면 증발해 불리해서, 가입 선물
    // 이용권(WelcomeGiftService)으로 대체됐다.
    private int chatDailyLimit = 5;
    private int assessmentDailyLimit = 1;

    // in-flight 잠금의 자동 만료. 서버가 죽어 해제가 유실돼도 이 시간이 지나면 풀린 것으로 본다.
    private long inFlightTtlSeconds = 120;

    // 한 유저가 동시에 진행할 수 있는 생성(대화 답변, 진단) 최대 건수. 사연 여러 개로 동시에 쏘아
    // 후차감 검사를 한꺼번에 통과시켜 한도를 크게 넘기는 것을 막는 상한.
    private int maxConcurrentGenerationsPerUser = 3;
}
