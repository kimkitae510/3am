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
    // 반드시 해당 종류의 LLM 타임아웃보다 커야 한다 — 짧으면 아직 진행 중인 생성 위로 두 번째
    // 요청이 만료된 락을 뺏어(acquire의 IF locked_until < now) 동시 생성이 된다.
    // 채팅 50초 < 60초, 진단 90초 < 100초. 정상 종료든 실패든 콜백에서 즉시 반납하므로
    // 이 값이 실제로 쓰이는 건 프로세스 강제 종료나 콜백 유실뿐이다.
    private long chatLockTtlSeconds = 60;
    private long assessmentLockTtlSeconds = 100;

    // 채팅 연속 실패 가드. 진단(2회/3분)보다 느슨하다 — 채팅은 턴이 짧고 잦아 2회면 일시 장애에
    // 걸리고, 대화 리듬이라 3분은 이탈로 이어진다. 1분이면 분당 1회로 캡돼 비용 방어엔 충분하다.
    private int chatFailStreakLimit = 3;
    private long chatFailCooldownSeconds = 60;

    public long lockTtlSeconds(UsageKind kind) {
        return kind == UsageKind.CHAT ? chatLockTtlSeconds : assessmentLockTtlSeconds;
    }

    public int signupGift(UsageKind kind) {
        return kind == UsageKind.CHAT ? signupGiftChat : signupGiftAssessment;
    }
}
