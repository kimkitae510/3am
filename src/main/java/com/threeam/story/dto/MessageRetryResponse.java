package com.threeam.story.dto;

import lombok.Getter;

// 재시도 접수 응답. 답은 백그라운드에서 만들어지므로 여기서 돌려줄 말풍선이 없고,
// 대신 폴링 기준 id를 준다 — 폴백을 지웠으니 클라이언트가 들고 있던 id는 이미 없는 행이다.
@Getter
public class MessageRetryResponse {

    private final Long pollAfterId;

    public MessageRetryResponse(Long pollAfterId) {
        this.pollAfterId = pollAfterId;
    }
}
