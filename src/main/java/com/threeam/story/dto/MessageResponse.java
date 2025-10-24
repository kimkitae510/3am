package com.threeam.story.dto;

import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class MessageResponse {

    private final Long id;
    private final MessageRole role;
    private final String content;
    private final LocalDateTime createdAt;

    private MessageResponse(Long id, MessageRole role, String content, LocalDateTime createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt());
    }
}
