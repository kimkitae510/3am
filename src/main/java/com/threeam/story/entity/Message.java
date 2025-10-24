package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
// 커서 페이지네이션("이 사연의 id < cursor 최신순 N개")을 인덱스 범위 스캔으로 처리하기 위한 복합 인덱스.
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_story_id", columnList = "story_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Message(Story story, MessageRole role, String content) {
        this.story = story;
        this.role = role;
        this.content = content;
    }

    public static Message user(Story story, String content) {
        return Message.builder().story(story).role(MessageRole.USER).content(content).build();
    }

    public static Message assistant(Story story, String content) {
        return Message.builder().story(story).role(MessageRole.ASSISTANT).content(content).build();
    }
}
