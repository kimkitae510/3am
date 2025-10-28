package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafetyScannerTest {

    private final SafetyScanner scanner = new SafetyScanner();

    @Test
    @DisplayName("위기 키워드가 있으면 danger")
    void danger_whenKeyword() {
        assertThat(scanner.isDanger(List.of("그냥 다 죽고 싶어"))).isTrue();
        assertThat(scanner.isDanger(List.of("걔가 나를 때렸어"))).isTrue();
    }

    @Test
    @DisplayName("평범한 이별 얘기는 danger 아님")
    void safe_whenNoKeyword() {
        assertThat(scanner.isDanger(List.of("걔가 먼저 헤어지자 했어", "아직 미련이 남아"))).isFalse();
    }

    @Test
    @DisplayName("빈 대화/널은 danger 아님")
    void safe_whenEmpty() {
        assertThat(scanner.isDanger(List.of())).isFalse();
    }
}
