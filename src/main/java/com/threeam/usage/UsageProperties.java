package com.threeam.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 사용량 제한 설정. 한도 수치는 실제 비용 추이를 보고 조정한다.
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    private int chatDailyLimit = 50;
    private int assessmentDailyLimit = 3;

    // in-flight 잠금의 자동 만료. 서버가 죽어 해제가 유실돼도 이 시간이 지나면 풀린 것으로 본다.
    private long inFlightTtlSeconds = 120;

    private final Api api = new Api();

    // LLM 외 일반 API의 분당 빈도 제한. 인증 전 엔드포인트는 IP, 인증 후는 유저 기준.
    @Getter
    @Setter
    public static class Api {
        private int loginPerMinute = 5;      // 비밀번호 무차별 시도 차단
        private int signupPerMinute = 3;     // 계정 양산(쿼터 우회) 억제
        private int reissuePerMinute = 10;
        private int writePerMinute = 20;     // 방 생성/삭제/로그아웃 등 쓰기
        private int readPerMinute = 120;     // 목록/대화 조회/폴링 등 읽기
    }
}
