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
    // 답을 못 받아 폴백이 저장된 턴. 화면은 이 값으로 재시도 버튼을 띄운다 —
    // 프론트가 폴백 문구를 복사해 문자열로 비교하면 문구를 고칠 때마다 두 곳이 어긋난다.
    private final boolean failed;

    private MessageResponse(Long id, MessageRole role, String content, LocalDateTime createdAt,
                            boolean failed) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.failed = failed;
    }

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt(),
                message.isFallback());
    }
}
