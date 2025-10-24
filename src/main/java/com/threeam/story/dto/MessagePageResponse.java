package com.threeam.story.dto;

import java.util.List;
import lombok.Getter;

@Getter
public class MessagePageResponse {

    private final List<MessageResponse> messages; // 과거→현재 순(화면 표시용)
    private final Long nextCursor;                // 더 과거를 요청할 때 넘길 커서(이번 배치의 가장 오래된 id). 없으면 null
    private final boolean hasNext;                // 더 과거 메시지 존재 여부

    public MessagePageResponse(List<MessageResponse> messages, Long nextCursor, boolean hasNext) {
        this.messages = messages;
        this.nextCursor = nextCursor;
        this.hasNext = hasNext;
    }
}
