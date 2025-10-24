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

    private StoryResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StoryResponse from(Story story) {
        return new StoryResponse(
                story.getId(),
                story.getTitle(),
                story.getCreatedAt(),
                story.getUpdatedAt());
    }
}
