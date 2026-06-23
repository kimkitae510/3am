package com.threeam.assessment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 정밀 판독(2호출) 지시 전문. 추론 원칙, 네 질문의 경계, 반대 증거 규칙 등 판독 노하우는
// 서비스 자산이라 저장소에 올리지 않고 로컬 reading.yml(gitignore)로 주입한다. rubric.yml과 같은 방식.
// 여기 기본값은 자리표시자 — 로컬 파일이 없으면 서비스는 뜨지만 판독 품질은 크게 떨어진다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.reading")
public class ReadingProperties {

    private String guide = "너는 확정된 재회 판정 위에 왜 그런지를 서술하는 분석가다.";
}
