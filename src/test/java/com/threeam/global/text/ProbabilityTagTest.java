package com.threeam.global.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProbabilityTagTest {

    @Test
    @DisplayName("꼬리표를 떼어내고 값만 남긴다 — 유저에게는 본문만 간다")
    void stripsTagAndKeepsValue() {
        ProbabilityTag.Result result = ProbabilityTag.strip("지금은 쉽지 않은 판이야\n[[가능성:25]]");

        assertThat(result.text()).isEqualTo("지금은 쉽지 않은 판이야");
        assertThat(result.probability()).isEqualTo(25);
    }

    @Test
    @DisplayName("방향을 말하지 않은 턴은 값이 없다 — 그것도 기록이다")
    void noTagMeansNoValue() {
        ProbabilityTag.Result result = ProbabilityTag.strip("그 얘기 좀 더 들려줄래");

        assertThat(result.text()).isEqualTo("그 얘기 좀 더 들려줄래");
        assertThat(result.probability()).isNull();
    }

    // 형식이 조금 어긋났다고 기록을 잃으면 실측 표본에 구멍이 난다.
    @Test
    @DisplayName("공백과 전각 콜론이 섞여도 읽어낸다")
    void toleratesLooseFormat() {
        assertThat(ProbabilityTag.strip("답변 [[ 가능성 ： 70 ]]").probability()).isEqualTo(70);
    }

    @Test
    @DisplayName("여러 개가 붙어 오면 마지막 것이 결론이다")
    void lastTagWins() {
        assertThat(ProbabilityTag.strip("[[가능성:40]] 다시 보니 [[가능성:55]]").probability())
                .isEqualTo(55);
    }

    @Test
    @DisplayName("범위를 벗어난 숫자는 버린다")
    void ignoresOutOfRange() {
        assertThat(ProbabilityTag.strip("[[가능성:150]]").probability()).isNull();
    }
}
