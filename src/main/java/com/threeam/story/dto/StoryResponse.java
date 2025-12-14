package com.threeam.story.dto;

import com.threeam.story.entity.Story;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class StoryResponse {

    private final Long id;
    private final String title;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final boolean unread; // 마지막으로 읽은 뒤 새 활동이 있음 — 목록의 안읽음 배지용

    private StoryResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt,
                          boolean unread) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.unread = unread;
    }

    public static StoryResponse from(Story story) {
        return new StoryResponse(
                story.getId(),
                story.getTitle(),
                story.getCreatedAt(),
                story.getUpdatedAt(),
                story.hasUnread());
    }
}
