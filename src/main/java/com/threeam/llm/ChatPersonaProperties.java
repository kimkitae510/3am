package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 채팅 프롬프트 설정. 실제 문구 전문은 저장소에 올리지 않고 로컬 yml(gitignore)로 주입한다.
// 여기 기본값은 자리표시자 겸, 로컬 파일이 없어도 서비스가 도는 안전값.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.chat")
public class ChatPersonaProperties {

    private String persona = "당신은 이별을 겪은 사람의 곁을 지키는 대화 상대입니다.";

    // 프롬프트 맨 끝에 붙는 출력 직전 점검. 리마인더가 길어지면서 중간 규칙이 안 닿는 게
    // 실측돼(글자 그대로 금지한 문구가 그대로 출력됨) 제일 잘 새는 것만 끝으로 뺐다.
    // 진단의 '출력 직전 마지막 점검'(ReunionLlm)과 같은 장치다.
    private String finalCheck = "";

    // 진단 미니 라인의 지시문 꼬리. 코드에는 데이터(확률, 유형)만 있고 지시 문구는

    // 매 턴 대화 뒤에 주입되는 질문 원칙 리마인더. 페르소나 중간의 질문 규칙이 비결정적으로
    // 새는 실측(같은 사연에 질문 0개/3개) 대응 — 블록 안에 첫 답변/이후 답변 규칙이 다 있어
    // 턴 구분은 모델이 대화를 보고 한다. 비면 미주입.
    private String questionReminder = "";
}
