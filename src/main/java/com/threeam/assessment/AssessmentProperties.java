package com.threeam.assessment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 재회 진단 루브릭 설정. 감점 앵커, 오판 교정 규칙 등 진단 노하우 전문은 이 서비스의 핵심이라
// 저장소에 올리지 않고 로컬 rubric.yml(gitignore)로 주입한다. 채팅 persona.yml과 같은 방식.
// 여기 기본값은 자리표시자 — 로컬 파일이 없으면 서비스는 뜨지만 진단 품질은 크게 떨어진다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.assessment")
public class AssessmentProperties {

    private String rubric = "너는 이별한 사람의 재회 가능성을 냉정하게 진단하는 회의론자다.";
}
