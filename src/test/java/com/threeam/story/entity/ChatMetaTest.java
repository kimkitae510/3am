package com.threeam.story.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// turn-2 답변 끝의 내부 메타데이터. 안 떼면 JSON이 그대로 말풍선에 찍히고,
// 다음 턴 프롬프트에도 어시스턴트 발화로 실려 모델이 turn-rest에서도 같은 형식을 이어간다.
class ChatMetaTest {

    private static final String REPLY = """
            지금은 상대가 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.

            ---chat-meta---
            {"reunionDirection":"UNCERTAIN"}""";

    @Test
    @DisplayName("마커 이하를 잘라내고 본문만 남긴다")
    void stripsMetaTail() {
        assertThat(ChatMeta.strip(REPLY))
                .isEqualTo("지금은 상대가 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.")
                .doesNotContain("reunionDirection");
    }

    @Test
    @DisplayName("방향 값을 읽는다")
    void readsDirection() {
        assertThat(ChatMeta.direction(REPLY)).isEqualTo(ReunionDirection.UNCERTAIN);
    }

    // JSON 파서를 안 쓰는 이유가 이것이다 — 한 칸짜리 구조에 파싱 실패를 새로 만들 이유가 없다.
    @Test
    @DisplayName("따옴표나 공백이 흐트러져도 값은 건진다")
    void toleratesLooseFormatting() {
        assertThat(ChatMeta.direction("본문\n---chat-meta---\n{ reunionDirection : POSITIVE }"))
                .isEqualTo(ReunionDirection.POSITIVE);
        assertThat(ChatMeta.direction("본문\n---chat-meta---\n{\"reunionDirection\": \"negative\"}"))
                .isEqualTo(ReunionDirection.NEGATIVE);
    }

    // 없는 방향을 지어내느니 지난 값을 유지하는 편이 낫다.
    @Test
    @DisplayName("마커가 없거나 사전 밖 값이면 null이고 본문은 그대로다")
    void nullWhenAbsentOrUnknown() {
        assertThat(ChatMeta.direction("마커 없는 평범한 답변")).isNull();
        assertThat(ChatMeta.direction("본문\n---chat-meta---\n{\"reunionDirection\":\"MAYBE\"}")).isNull();
        assertThat(ChatMeta.strip("마커 없는 평범한 답변")).isEqualTo("마커 없는 평범한 답변");
        assertThat(ChatMeta.strip(null)).isNull();
    }

    // turn-rest는 chat-meta를 안 내는 게 정상이라, null로 덮으면 방향이 턴마다 사라졌다 생긴다.
    @Test
    @DisplayName("값을 못 읽은 턴은 사연의 기존 방향을 덮지 않는다")
    void keepsPreviousDirectionWhenAbsent() {
        Story story = Story.builder().userId(1L).title("사연").build();
        story.updateReunionDirection(ReunionDirection.NEGATIVE);

        story.updateReunionDirection(ChatMeta.direction("마커 없는 자유 상담 답변"));

        assertThat(story.getReunionDirection()).isEqualTo(ReunionDirection.NEGATIVE);
    }
}
