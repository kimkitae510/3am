package com.threeam.conversation.entity;

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
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 새 메시지가 들어올 때 touch()로 직접 갱신한다. 대화방 목록을 최근 활동순으로 정렬하기 위함.
    // (메시지는 별도 엔티티라 @UpdateTimestamp로는 대화방 갱신이 잡히지 않는다.)
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Conversation(Long userId, String title) {
        this.userId = userId;
        this.title = title;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
