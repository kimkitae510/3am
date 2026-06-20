package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 평소 상담에는 분석을 안 싣는 정책이라, 이 판정이 놓치면 유저가 확률을 물었는데 분석을
// 못 보고 답한다. 반대로 오탐이 잦으면 안 물어본 턴마다 확률이 실려 정책이 무의미해진다.
class DiagnosisMentionTest {

    @ParameterizedTest
    @DisplayName("분석 결과를 직접 물으면 잡아낸다")
    @ValueSource(strings = {
            "분석은 70% 나왔는데 왜 지금 연락하지 말라는 거예요?",
            "진단 결과가 왜 그렇게 나왔어요",
            "재회 가능성 몇이었죠",
            "생각보다 낮게 나왔는데 이유가 뭔가요",
            "아까랑 다르게 말하시는 것 같아요",
            "확률이 왜 이래요",
    })
    void catchesDiagnosisQuestions(String message) {
        assertThat(DiagnosisMention.referenced(message)).isTrue();
    }

    // "결과"를 단독 키워드로 두면 여기 걸린다 — 안 물어본 턴에 확률이 실려 정책이 무너진다.
    @ParameterizedTest
    @DisplayName("일상 대화는 그냥 지나간다")
    @ValueSource(strings = {
            "만난 결과가 어땠냐면 그냥 어색했어요",
            "걔 친구가 갑자기 제 스토리를 봤는데 이건 뭐예요",
            "어제 갑자기 먼저 연락이 왔어요",
            "지금 연락해도 될까요?",
            "제가 다 망친 걸까요",
    })
    void ignoresOrdinaryTalk(String message) {
        assertThat(DiagnosisMention.referenced(message)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("빈 입력은 판정하지 않는다")
    @ValueSource(strings = {"", "   "})
    void ignoresBlank(String message) {
        assertThat(DiagnosisMention.referenced(message)).isFalse();
    }
}
