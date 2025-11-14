package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 사연의 "사실 원장". 롤링 요약(StoryMemory)이 통째로 교체되며 사실을 잃는 것과 달리,
// 결정적 사실(바람·이별 통보 주체·연락 상태 변화 등)은 여기 한 줄씩 append-only로 쌓인다.
// 감정 흐름은 StoryMemory가, 사건·사실은 여기가 담당한다.
@Entity
@Table(name = "story_facts", indexes = {
        @Index(name = "idx_story_facts_story", columnList = "story_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryFact {

    public static final int MAX_LENGTH = 200;

    // 한 번의 추출에서 받아주는 최대 개수. 정상 대화에선 닿지 않는 폭주 방어용 안전핀.
    // 원장 자체에는 상한이 없다(중요한 사실은 자르지 않는다) — 대신 입구에서 이상 동작만 막는다.
    public static final int MAX_PER_EXTRACT = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    // "일주일 전 상대에게서 연락 옴"처럼 시점 표현은 문장 안에 담는다. 절대 시점은 createdAt으로 보정.
    @Column(nullable = false, length = MAX_LENGTH)
    private String fact;

    // 어느 진단에서 추출됐는지(추적용). 물리 FK는 걸지 않는다.
    @Column
    private Long sourceAssessmentId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private StoryFact(Long storyId, String fact, Long sourceAssessmentId) {
        this.storyId = storyId;
        this.fact = fact;
        this.sourceAssessmentId = sourceAssessmentId;
    }

    public static StoryFact of(Long storyId, String fact, Long sourceAssessmentId) {
        return new StoryFact(storyId, fact, sourceAssessmentId);
    }
}
