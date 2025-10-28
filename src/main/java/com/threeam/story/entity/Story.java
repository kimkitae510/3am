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
import org.hibernate.annotations.CreationTimestamp;

// 사연 = 한 사람(전 연인)에 대한 상담 스레드 전체. 밑에 메시지들이 달리고, 재회 확률도 여기에 귀속된다.
@Entity
@Table(name = "stories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Story {

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

    // 새 메시지가 들어올 때 touch()로 직접 갱신한다. 사연 목록을 최근 활동순으로 정렬하기 위함.
    // (메시지는 별도 엔티티라 @UpdateTimestamp로는 사연 갱신이 잡히지 않는다.)
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 소프트 딜리트: 물리 삭제 대신 시각만 찍는다. 대화·진단은 남겨둬야 할 기록이라 지우지 않는다.
    // null이면 살아있는 사연. 조회 쿼리는 deletedAt IS NULL만 노출한다.
    @Column
    private LocalDateTime deletedAt;

    @Builder
    private Story(Long userId, String title) {
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

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
