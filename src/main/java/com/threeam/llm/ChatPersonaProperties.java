package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 채팅 페르소나 설정. 실제 문구 전문은 저장소에 올리지 않고 로컬 persona.yml(gitignore)로 주입한다.
// 여기 기본값은 자리표시자 겸, 로컬 파일이 없어도 서비스가 도는 안전값.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.chat")
public class ChatPersonaProperties {

    private String persona = "당신은 이별을 겪은 사람의 곁을 지키는 다정한 대화 상대입니다.";
}
