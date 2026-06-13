package com.threeam.llm;

import java.util.List;
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
    // 분석의 '출력 직전 마지막 점검'(ReunionLlm)과 같은 장치다.
    private String finalCheck = "";

    // 분석 미니 라인의 지시문 꼬리. 코드에는 데이터(확률, 유형)만 있고 지시 문구는

    // 채팅에 실리는 분석 재료 블록의 사용 규칙. 코드는 확률과 사실 목록만 만들고 이 문구가
    // 그 뒤에 붙는다. 비우면 데이터만 실려 모델이 그걸 어떻게 쓸지 모른 채 대화에 끌어다 쓴다(실측).
    private String assessmentGuide = "";

    // 답변 회차별 지시. 대화 뒤에 이번 회차 것 하나만 실린다. 비면 미주입.
    private String turn1 = "";
    private String turn2 = "";
    private String turn3 = "";
    private String turnRest = "";

    // 회차별로만 실리는 규칙 묶음. 매 회차에 다 실으면 그 회차가 맡지 않은 일까지 하게 된다.
    private String question = "";
    private String analysis = "";
    private String partnerAnalysis = "";
    private String action = "";

    // 답변 회차(1부터)에 해당하는 지시. 4회차부터는 자유 대화 블록이다.
    public String turnGuide(int answerNo) {
        return switch (answerNo) {
            case 1 -> turn1;
            case 2 -> turn2;
            case 3 -> turn3;
            default -> turnRest;
        };
    }

    // 이번 회차에 함께 실을 규칙 묶음. 회차가 맡지 않은 일의 규칙은 싣지 않는다 —
    // 규칙이 실려 있으면 지시로 미뤄도 결국 한다(실측). 2회차에 지침까지 실었더니
    // 분석을 결론문 몇 줄로 치고 넘어가 서사가 사라졌다.
    public List<String> sectionsFor(int answerNo) {
        return switch (answerNo) {
            case 1 -> List.of(question);
            case 2 -> List.of(analysis, partnerAnalysis);
            case 3 -> List.of(analysis, partnerAnalysis, action);
            default -> List.of(question, analysis, partnerAnalysis, action);
        };
    }
}
