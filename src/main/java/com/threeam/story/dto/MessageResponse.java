package com.threeam.story.dto;

import com.threeam.chip.dto.ChipView;
import com.threeam.story.entity.ChatMeta;
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
    // 이 답변 밑에 그릴 추천 질문. 마지막 답변에만 채워 보낸다 — 지난 답변에도 그리면
    // 대화 곳곳에 눌리는 칩이 남아 어느 시점의 추천인지 알 수 없다.
    private final List<ChipView> chips;

    private MessageResponse(Long id, MessageRole role, String content, LocalDateTime createdAt,
                            boolean failed, List<String> questions, List<ChipView> chips) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.failed = failed;
        this.questions = questions;
        this.chips = chips;
    }

    public static MessageResponse from(Message message) {
        return from(message, List.of());
    }

    public static MessageResponse from(Message message, List<ChipView> chips) {
        // 이제는 저장 전에 떼지만(MessageTxService), 그 코드가 생기기 전에 저장된 대화에는
        // JSON이 본문에 박혀 있다. 기록은 고치지 않으므로 표시에서 가린다.
        // 질문 분리보다 먼저 뗀다 — 뒤에 하면 마지막 질문에 JSON이 붙어 입력 카드로 나간다.
        String raw = ChatMeta.strip(message.getContent());
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
                questions,
                chips);
    }
}
