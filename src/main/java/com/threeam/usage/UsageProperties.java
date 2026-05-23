package com.threeam.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 사용량 설정. 잔여 회수는 전부 이용권(entitlements) 하나로 관리한다 — 일일 무료 쿼터는
// 폐지됐다(유저당 월 원가가 1만원을 넘어 지속 불가, 2026-05 실비 실측). 무료로 주는 것도
// 이용권으로 지급하므로, 나중에 이벤트나 재방문 선물을 붙일 때도 지급 한 줄이면 된다.
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    // 가입 선물. 이월되는 이용권이라 그날 못 써도 증발하지 않는다.
    private int signupGiftChat = 5;
    private int signupGiftAssessment = 1;

    // 게스트 체험. 진단은 0회 — 계정 연결을 유도하는 지점이라 아예 주지 않는다.
    private int guestTrialChat = 5;

    // 생성 락의 자동 만료(TTL). LLM 호출이 실패로 락을 못 풀어도 이 시간이 지나면 풀린 것으로 본다.
    // 종류별로 다르게 둔다 — TTL이 LLM 타임아웃보다 짧으면, 아직 진행 중인 생성 위로 두 번째
    // 요청이 락을 뺏어 동시 생성(중복 차감, 원장 레이스)이 생기기 때문이다.
    // 채팅은 응답이 수 초라 20초로 짧게(좀비 락도 빨리 풀림), 진단은 deep 타임아웃(90초)보다
    // 넉넉한 100초로 둔다. 정상 생성은 끝나면 즉시 락을 반납하므로 이 값은 실패 시 상한일 뿐이다.
    private long chatLockTtlSeconds = 20;
    private long assessmentLockTtlSeconds = 100;

    public long lockTtlSeconds(UsageKind kind) {
        return kind == UsageKind.CHAT ? chatLockTtlSeconds : assessmentLockTtlSeconds;
    }

    public int signupGift(UsageKind kind) {
        return kind == UsageKind.CHAT ? signupGiftChat : signupGiftAssessment;
    }
}
