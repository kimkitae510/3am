package com.threeam.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 사용량 제한 설정. 한도 수치는 실제 비용 추이를 보고 조정한다.
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    private int chatDailyLimit = 30;
    private int assessmentDailyLimit = 3;

    // in-flight 잠금의 자동 만료. 서버가 죽어 해제가 유실돼도 이 시간이 지나면 풀린 것으로 본다.
    private long inFlightTtlSeconds = 120;
}
