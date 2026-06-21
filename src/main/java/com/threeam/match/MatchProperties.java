package com.threeam.match;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 유료 사례 매칭의 선택 지시문. 어떤 기준으로 고르고 무엇을 근거로 해설을 쓸지가 이 기능의
// 값어치라 소스에 두지 않고 로컬 rubric.yml(gitignore)로 주입한다 — 루브릭, 페르소나와 같은 방식.
// 비어 있으면 최소 지시만으로 호출되고, 그때는 서버 점수 순 폴백과 다를 게 없어진다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.match")
public class MatchProperties {

    private String selectGuide = "";
}
