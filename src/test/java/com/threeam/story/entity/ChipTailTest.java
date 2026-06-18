package com.threeam.story.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 상담 답변 맨 끝의 추천 질문 블록. 안 떼면 JSON이 그대로 말풍선에 찍히고,
// 다음 턴 프롬프트에도 어시스턴트 발화로 실려 모델이 형식을 이어간다.
class ChipTailTest {

    private static final String REPLY = """
            지금은 먼저 연락하지 않는 편이 낫습니다.

            ---chips---
            [{"id":"CONTACT_NOW","label":"토요일 전에 먼저 연락해도 될까요?"}]""";

    @Test
    @DisplayName("마커 이하를 잘라내고 본문만 남긴다")
    void stripsTail() {
        assertThat(ChipTail.strip(REPLY))
                .isEqualTo("지금은 먼저 연락하지 않는 편이 낫습니다.")
                .doesNotContain("CONTACT_NOW");
    }

    @Test
    @DisplayName("꼬리의 JSON 배열만 떼어낸다")
    void extractsJson() {
        assertThat(ChipTail.json(REPLY))
                .startsWith("[").endsWith("]").contains("CONTACT_NOW");
    }

    // 본체는 상담 답변이다. 꼬리가 깨져도 칩만 안 뜨고 답변은 멀쩡해야 한다.
    @Test
    @DisplayName("마커가 없거나 배열이 깨져도 본문은 그대로다")
    void toleratesMissingOrBrokenTail() {
        assertThat(ChipTail.json("마커 없는 평범한 답변")).isNull();
        assertThat(ChipTail.json("본문\n---chips---\n괄호가 없다")).isNull();
        assertThat(ChipTail.strip("마커 없는 평범한 답변")).isEqualTo("마커 없는 평범한 답변");
        assertThat(ChipTail.strip(null)).isNull();
        assertThat(ChipTail.json(null)).isNull();
    }

    // chat-meta와 나란히 붙는다. 둘 다 떼어야 본문만 남는다.
    @Test
    @DisplayName("chat-meta와 함께 붙어도 둘 다 떨어진다")
    void stripsAlongsideChatMeta() {
        String reply = """
                본문입니다.

                ---chat-meta---
                {"reunionDirection":"UNCERTAIN"}
                ---chips---
                [{"id":"CONTACT_NOW"}]""";

        assertThat(ChipTail.strip(ChatMeta.strip(reply))).isEqualTo("본문입니다.");
        assertThat(ChatMeta.direction(reply)).isEqualTo(ReunionDirection.UNCERTAIN);
        assertThat(ChipTail.json(reply)).contains("CONTACT_NOW");
    }
}
