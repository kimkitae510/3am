package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 채팅 프롬프트 설정. 실제 문구 전문은 저장소에 올리지 않고 로컬 yml(gitignore)로 주입한다
// — 페르소나는 persona.yml, 매 턴 리마인더는 reminder.yml.
// 여기 기본값은 자리표시자 겸, 로컬 파일이 없어도 서비스가 도는 안전값.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.chat")
public class ChatPersonaProperties {

    private String persona = "당신은 이별을 겪은 사람의 곁을 지키는 대화 상대입니다.";

    // 페르소나 깊숙한 규칙은 긴 프롬프트에서 자주 무시된다(실측) — 제일 잘 어기는 것만 골라
    // 프롬프트 맨 끝에 매 턴 다시 박는다. 빈 문자열이면 주입 자체를 건너뛴다.
    private String reminder = "";

    // 직전 답변이 질문으로 끝났을 때만 덧붙는 리마인더(매 턴 질문으로 끝내는 습관 차단).
    private String endingReminder = "";
}
