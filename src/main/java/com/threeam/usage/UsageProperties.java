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

    // 생성 락의 자동 만료(TTL). LLM 호출이 실패로 락을 못 풀어도 이 시간이 지나면 풀린 것으로 본다.
    // 종류별로 다르게 둔다 — TTL이 LLM 타임아웃보다 짧으면, 아직 진행 중인 생성 위로 두 번째
    // 요청이 락을 뺏어 동시 생성(쿼터 초과, 원장 레이스)이 생기기 때문이다.
    // 채팅은 응답이 수 초라 20초로 짧게(좀비 락도 빨리 풀림), 진단은 deep 타임아웃(60초)보다
    // 넉넉한 70초로 둔다. 정상 생성은 끝나면 즉시 락을 반납하므로 이 값은 실패 시 상한일 뿐이다.
    private long chatLockTtlSeconds = 20;
    private long assessmentLockTtlSeconds = 70;

    public long lockTtlSeconds(UsageKind kind) {
        return kind == UsageKind.CHAT ? chatLockTtlSeconds : assessmentLockTtlSeconds;
    }
}
