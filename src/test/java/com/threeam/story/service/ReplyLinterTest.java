package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReplyLinterTest {

    private final ReplyLinter linter = new ReplyLinter();

    @Test
    @DisplayName("판세 어휘 - 조사가 붙어 홀로 쓰인 '판'을 잡는다")
    void catchesBoardWords() {
        assertThat(linter.violatedRules("만나기 전에 판을 정확히 읽으려면")).contains("판세어휘");
        assertThat(linter.violatedRules("주도권이 걔한테 넘어갔어")).contains("판세어휘");
    }

    @Test
    @DisplayName("판세 어휘 - 판단, 재판처럼 다른 낱말의 일부는 잡지 않는다(오탐이 섞이면 지표를 못 믿는다)")
    void ignoresCompoundWords() {
        assertThat(linter.violatedRules("네 판단이 맞아")).isEmpty();
        assertThat(linter.violatedRules("재판까지 갈 일은 아니야")).isEmpty();
        assertThat(linter.violatedRules("간판이 바뀌었대")).isEmpty();
    }

    @Test
    @DisplayName("어미 - '~거다'는 잡고 '~거야'는 통과시킨다")
    void catchesWrittenEnding() {
        assertThat(linter.violatedRules("도망친 거다")).contains("거다어미");
        assertThat(linter.violatedRules("도망친 거야")).isEmpty();
    }

    @Test
    @DisplayName("형식 - 불릿과 마크다운을 잡는다")
    void catchesFormatting() {
        assertThat(linter.violatedRules("- 첫째로 이건")).contains("불릿");
        assertThat(linter.violatedRules("이별·재회 얘기")).contains("불릿");
        assertThat(linter.violatedRules("**중요한 건**")).contains("마크다운");
    }

    @Test
    @DisplayName("정상 답변은 아무것도 걸리지 않는다")
    void cleanReplyPasses() {
        assertThat(linter.violatedRules("걔가 조율 대신 이별을 고른 거야\n\n지금은 연락 안 하는 게 맞아")).isEmpty();
    }
}
