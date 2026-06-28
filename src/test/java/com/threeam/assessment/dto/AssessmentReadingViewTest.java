package com.threeam.assessment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 판독 뷰 조립 — 특히 변동내역(델타)이 LLM 서술이 아니라 두 판정의 결정론 diff인지 검증한다.
class AssessmentReadingViewTest {

    private Assessment assessment(int probability, FactorLevel partnerSignal,
                                  FactorLevel replacement) {
        return Assessment.builder()
                .storyId(1L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(probability)
                .reason("총평")
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, partnerSignal, "근거", null, null))
                .factor(AssessmentFactor.of(FactorName.REPLACEMENT, replacement, "근거", null, null))
                .build();
    }

    private ReadingDraft report() {
        return new ReadingDraft(
                new ReadingDraft.ProbabilityReading("확률 판독", List.of("F01")),
                List.of(new ReadingDraft.Chapter("아이브로", "제목", "CORE_CONTRADICTION", "답",
                        "서술", null, null, List.of("F01"))),
                null, null, null,
                new ReadingDraft.Reselect("제목", "답", "서술", List.of()),
                new ReadingDraft.Fin("관계 재평가 중", List.of()),
                new ReadingDraft.Internal("MIXED", "UNSTABLE", "PRESENT", "CONDITIONAL"));
    }

    @Test
    @DisplayName("델타는 base 대비 바뀐 요인만 담고, 확률은 양쪽 값을 그대로 싣는다")
    void delta_changedFactorsOnly() {
        Assessment base = assessment(40, FactorLevel.UNFAVORABLE, FactorLevel.NEUTRAL);
        Assessment current = assessment(55, FactorLevel.FAVORABLE, FactorLevel.NEUTRAL);

        AssessmentResponse.Reading view = AssessmentResponse.Reading.of(report(), current, base, null);

        assertThat(view.delta()).isNotNull();
        assertThat(view.delta().probabilityFrom()).isEqualTo(40);
        assertThat(view.delta().probabilityTo()).isEqualTo(55);
        assertThat(view.delta().factors()).hasSize(1);
        assertThat(view.delta().factors().get(0).name()).isEqualTo("상대신호");
        assertThat(view.delta().factors().get(0).from()).isEqualTo("불리");
        assertThat(view.delta().factors().get(0).to()).isEqualTo("유리");
    }

    @Test
    @DisplayName("base가 없으면(첫 판정) 델타 없이 리포트만 내린다")
    void delta_nullWithoutBase() {
        Assessment current = assessment(55, FactorLevel.FAVORABLE, FactorLevel.NEUTRAL);

        AssessmentResponse.Reading view = AssessmentResponse.Reading.of(report(), current, null, null);

        assertThat(view.delta()).isNull();
        assertThat(view.report().probabilityReading().reading()).isEqualTo("확률 판독");
        assertThat(view.report().chapters()).hasSize(1);
    }

    @Test
    @DisplayName("아무 요인도 안 변했으면 빈 목록 — '가늠이 이미 정확했다'도 정보다")
    void delta_emptyWhenUnchanged() {
        Assessment base = assessment(55, FactorLevel.FAVORABLE, FactorLevel.NEUTRAL);
        Assessment current = assessment(55, FactorLevel.FAVORABLE, FactorLevel.NEUTRAL);

        AssessmentResponse.Reading view = AssessmentResponse.Reading.of(report(), current, base, null);

        assertThat(view.delta()).isNotNull();
        assertThat(view.delta().factors()).isEmpty();
    }
}
