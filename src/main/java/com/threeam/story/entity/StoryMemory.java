package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사연별 "기억". LLM 컨텍스트 창(최근 N개)을 넘어가면 잊히는 오래된 사실을,
// 여기 롤링 요약으로 눌러 담아 매 대화, 진단마다 다시 넣어준다.
// 바람/먼저 이별 통보/싸움 같은 결정적 사실도 enum이 아니라 이 요약 텍스트에 자연어로 남는다.
@Entity
@Table(name = "story_memories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 사연 1:1. 조회 키로 쓰므로 유니크.
    @Column(nullable = false, unique = true)
    private Long storyId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private StoryMemory(Long storyId, String summary) {
        this.storyId = storyId;
        this.summary = summary;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateSummary(String summary) {
        this.summary = summary;
        this.updatedAt = LocalDateTime.now();
    }
}
