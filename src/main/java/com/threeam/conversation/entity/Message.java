package com.threeam.conversation.entity;

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
// 커서 페이지네이션("이 대화방의 id < cursor 최신순 N개")을 인덱스 범위 스캔으로 처리하기 위한 복합 인덱스.
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation_id", columnList = "conversation_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Message(Conversation conversation, MessageRole role, String content) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
    }

    public static Message user(Conversation conversation, String content) {
        return Message.builder().conversation(conversation).role(MessageRole.USER).content(content).build();
    }

    public static Message assistant(Conversation conversation, String content) {
        return Message.builder().conversation(conversation).role(MessageRole.ASSISTANT).content(content).build();
    }
}
