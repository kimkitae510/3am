package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadingLlmTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReadingDraft read(String json) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(json));
        Assessment saved = Assessment.builder()
                .storyId(1L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(40)
                .reason("판정 총평")
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();
        AssessmentContext context = new AssessmentContext(List.of(),
                List.of(ChatMessage.user("사연")), null, null, null);
        return new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(context, saved).join();
    }

    // 유효한 판독 JSON의 뼈대. 테스트마다 일부만 바꿔 쓴다.
    private static String validJson(String nowState, String nowAnswer, String evidenceRows) {
        return """
                {
                  "overall": "통보는 차가웠지만 판이 닫히진 않았다.",
                  "narrative": "다툼이 쌓였고 마지막 날 통보가 나왔다.",
                  "now": {"state": "%s", "answer": "%s", "reading": "무반응은 소진의 신호"},
                  "resolve": {"state": "MODERATE", "answer": "굳은 결심은 아니다", "reading": "숙고보다 피로가 밀었다"},
                  "remain": {"state": "PRESENT", "answer": "마음이 정리된 근거는 없다", "reading": "관계를 부정하는 행동이 없다"},
                  "reselect": {"state": "CONDITIONAL", "answer": "조건이 바뀌면 움직인다", "closed": "정서적 여력", "open": "대화 가능성", "route": "거리두기 이후 재대화"},
                  "evidence": [%s],
                  "phase": "설득이 아니라 확인할 단계다.",
                  "narrativeTitle": "관계가 뒤집힌 순간은 언제였나",
                  "nowTitle": "상대는 지금 어떤 상태인가",
                  "resolveRemainTitle": "결심은 진짜였을까",
                  "reselectTitle": "다시 움직일 조건은"
                }
                """.formatted(nowState, nowAnswer, evidenceRows);
    }

    private static final String VALID_EVIDENCE = """
            {"question": "상대의지금", "source": "요인", "name": "상대신호", "direction": "불리", "fact": "두 달째 무반응", "interpretation": "소진 후 거리두기"},
            {"question": "남은마음", "source": "추가신호", "name": "기록보존", "direction": "유리", "fact": "사진을 지우지 않음", "interpretation": "단독으로는 약한 신호"}
            """;

    @Test
    @DisplayName("정상 JSON을 판독으로 파싱한다 (네 질문, 증거, 장 제목)")
    void parse_success() {
        ReadingDraft draft = read(validJson("DETACHED", "거리를 두는 중", VALID_EVIDENCE));

        assertThat(draft.overall()).contains("닫히진 않았다");
        assertThat(draft.now().state()).isEqualTo("DETACHED");
        assertThat(draft.resolve().state()).isEqualTo("MODERATE");
        assertThat(draft.remain().state()).isEqualTo("PRESENT");
        assertThat(draft.reselect().route()).isEqualTo("거리두기 이후 재대화");
        assertThat(draft.evidence()).hasSize(2);
        assertThat(draft.evidence().get(1).source()).isEqualTo("추가신호");
        assertThat(draft.phase()).contains("확인할 단계");
        assertThat(draft.chapterTitles())
                .containsEntry("narrative", "관계가 뒤집힌 순간은 언제였나")
                .containsKeys("now", "resolveRemain", "reselect");
    }

    @Test
    @DisplayName("사전 밖 state는 버리고 대체값으로 채운다 (salvage 경로 방어)")
    void parse_unknownState_fallsBack() {
        ReadingDraft draft = read(validJson("WEIRD_STATE", "거리를 두는 중", VALID_EVIDENCE));

        assertThat(draft.now().state()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("증거 검증 — 어휘 밖 질문과 가짜 요인 이름은 폐기, 추가신호는 이름 기준 3개까지")
    void parse_evidence_filtered() {
        ReadingDraft draft = read(validJson("DETACHED", "거리를 두는 중", """
                {"question": "엉뚱한질문", "source": "요인", "name": "상대신호", "direction": "불리", "fact": "사실", "interpretation": "해석"},
                {"question": "상대의지금", "source": "요인", "name": "숨은죄책감", "direction": "불리", "fact": "사실", "interpretation": "해석"},
                {"question": "상대의지금", "source": "추가신호", "name": "신호1", "direction": "유리", "fact": "사실", "interpretation": "해석"},
                {"question": "상대의지금", "source": "추가신호", "name": "신호2", "direction": "유리", "fact": "사실", "interpretation": "해석"},
                {"question": "상대의지금", "source": "추가신호", "name": "신호3", "direction": "유리", "fact": "사실", "interpretation": "해석"},
                {"question": "상대의지금", "source": "추가신호", "name": "신호4", "direction": "유리", "fact": "사실", "interpretation": "해석"},
                {"question": "결심강도", "source": "요인", "name": "통보온도", "direction": "불리", "fact": "다신 보지 말자", "interpretation": "격앙이 민 통보"}
                """));

        // 남는 것: 추가신호 1~3 + 통보온도. 어휘 밖 질문, 가짜 요인, 4번째 추가신호는 폐기.
        assertThat(draft.evidence()).hasSize(4);
        assertThat(draft.evidence().stream()
                .filter(e -> "추가신호".equals(e.source())).map(ReadingDraft.Evidence::name))
                .containsExactly("신호1", "신호2", "신호3");
    }

    @Test
    @DisplayName("답이 비어 있으면 판독으로 성립하지 않는다 — LlmException")
    void parse_blankAnswer_throws() {
        assertThatThrownBy(() -> read(validJson("DETACHED", "", VALID_EVIDENCE)))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LlmException.class);
    }
}
