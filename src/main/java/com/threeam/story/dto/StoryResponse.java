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
    private final String lastMessage; // 마지막 메시지 미리보기(한 줄, 잘림). 대화가 없으면 null

    private StoryResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt,
                          boolean unread, String lastMessage) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.unread = unread;
        this.lastMessage = lastMessage;
    }

    public static StoryResponse from(Story story) {
        return from(story, null);
    }

    public static StoryResponse from(Story story, String lastMessage) {
        return new StoryResponse(
                story.getId(),
                story.getTitle(),
                story.getCreatedAt(),
                story.getUpdatedAt(),
                story.hasUnread(),
                lastMessage);
    }
}
