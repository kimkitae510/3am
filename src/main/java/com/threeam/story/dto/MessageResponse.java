package com.threeam.story.dto;

import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class MessageResponse {

    // 상담자가 답변 끝에 붙이는 질문 구분선. 이 아래는 말풍선이 아니라 입력 카드로 나간다.
    private static final String QUESTION_MARKER = "---질문---";

    private final Long id;
    private final MessageRole role;
    private final String content;
    private final LocalDateTime createdAt;
    // 답을 못 받아 폴백이 저장된 턴. 화면은 이 값으로 재시도 버튼을 띄운다 —
    // 프론트가 폴백 문구를 복사해 문자열로 비교하면 문구를 고칠 때마다 두 곳이 어긋난다.
    private final boolean failed;
    // 화면이 입력칸으로 그릴 질문들. 본문(content)에서는 빠지지만 저장된 원문에는 남는다 —
    // 지우면 다음 턴과 분석이 상담자가 무엇을 물었는지 모른다.
    private final List<String> questions;

    private MessageResponse(Long id, MessageRole role, String content, LocalDateTime createdAt,
                            boolean failed, List<String> questions) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.failed = failed;
        this.questions = questions;
    }

    public static MessageResponse from(Message message) {
        String raw = message.getContent();
        int at = raw == null ? -1 : raw.indexOf(QUESTION_MARKER);
        String body = at < 0 ? raw : raw.substring(0, at).stripTrailing();
        List<String> questions = at < 0
                ? List.of()
                : raw.substring(at + QUESTION_MARKER.length()).lines()
                        .map(String::strip)
                        .filter(line -> !line.isBlank())
                        .toList();
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                body,
                message.getCreatedAt(),
                message.isFallback(),
                questions);
    }
}
