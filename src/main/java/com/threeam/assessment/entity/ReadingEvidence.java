package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 정밀 판독의 증거 한 줄. 요인(채점된 것)과 추가신호(채점 틀 밖 발견)가 같은 형태로
// 질문 밑에 꽂힌다. 값은 ReadingVocab에서 검증된 라벨 그대로 저장한다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadingEvidence {

    @Column(nullable = false, length = 20)
    private String question;        // "상대의지금" | "결심강도" | "남은마음" | "재선택"

    @Column(nullable = false, length = 10)
    private String source;          // "요인" | "추가신호"

    // 요인이면 FactorName 라벨, 추가신호면 자유 이름(파서가 개수와 길이를 막는다)
    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false, length = 10)
    private String direction;       // "유리" | "불리"

    @Column(nullable = false, length = 500)
    private String fact;            // 사연에서 관찰된 사실(인용)

    @Column(nullable = false, length = 500)
    private String interpretation;  // 이 사실이 상대의 상태에 대해 말해주는 것

    private ReadingEvidence(String question, String source, String name, String direction,
                            String fact, String interpretation) {
        this.question = question;
        this.source = source;
        this.name = name;
        this.direction = direction;
        this.fact = fact;
        this.interpretation = interpretation;
    }

    public static ReadingEvidence of(String question, String source, String name,
                                     String direction, String fact, String interpretation) {
        return new ReadingEvidence(question, source, name, direction, fact, interpretation);
    }
}
