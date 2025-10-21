package com.threeam.conversation.dto;

import com.threeam.conversation.entity.Conversation;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class ConversationResponse {

    private final Long id;
    private final String title;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private ConversationResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
