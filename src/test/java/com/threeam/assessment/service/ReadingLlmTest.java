package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadingLlmTest {

    @Mock
    private com.threeam.llm.LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Assessment saved() {
        return Assessment.builder()
                .storyId(1L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(62)
                .reason("판정 총평")
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();
    }

    private ReunionDiagnosis diagnosis(List<ReunionDiagnosis.ReadingFact> facts) {
        return new ReunionDiagnosis(ReunionVerdict.POSSIBLE, false, null, null, JumpRule.NONE,
                List.of(), null, null, List.of(), List.of(), null, null, "총평", List.of(),
                facts, List.of("다시 연락이 올까요?"), List.of());
    }

    private ReadingDraft read(String json, List<ReunionDiagnosis.ReadingFact> facts) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(json));
        return new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(saved(), diagnosis(facts), null, List.of(), "MID", null).join();
    }

    // 유효한 스토리북 JSON 뼈대. 테스트마다 일부만 바꿔 쓴다.
    private static String validJson(String mysteries, String nowState) {
        return """
                {
                  "coverVerdict": "다시 판단하는 상태에 가깝습니다.",
                  "coverReason": "직전까지 관계를 붙잡는 표현이 있었기 때문입니다.",
                  "mysteries": [%s],
                  "blockers": [
                    {"rank": 7, "title": "마지막 대화의 상처", "answer": "재선택을 막고 있습니다.", "reading": "감정 문제입니다.", "evidenceIds": ["F01"]},
                    {"rank": 7, "title": "현실 문제", "answer": "그대로 남아 있습니다.", "reading": "현실 문제입니다.", "evidenceIds": []}
                  ],
                  "reselect": {"title": "무엇이 달라지면 움직일까", "answer": "관계 대화의 재선택이 첫 조건입니다.", "open": ["회복 표현이 있었다"], "conditions": ["안전한 대화 경험"], "watchFor": ["기간 후 먼저 관계 얘기를 꺼내는지"]},
                  "phase": {"label": "확인의 구간", "reading": "설득이 아니라 확인할 구간입니다.", "chipSeeds": ["먼저 연락해도 될까?"]},
                  "followUp": null,
                  "internal": {"nowState": "%s", "resolveState": "UNSTABLE", "remainState": "PRESENT", "reselectState": "CONDITIONAL"}
                }
                """.formatted(mysteries, nowState);
    }

    private static final String MYSTERY = """
            {"title": "미래를 말한 다음날 왜 물러났을까?", "answer": "상처 때문에 가깝습니다.", "reading": "방향이 하루 만에 꺾인 것은 충격의 크기를 말합니다.", "principle": "감정의 존재와 이유를 먼저 알리는 방식이 필요합니다.", "evidenceIds": ["F01"], "covers": ["NOW", "RESOLVE"]}
            """;

    @Test
    @DisplayName("정상 JSON을 스토리북으로 파싱한다 (표지, 미스터리, 질문, 장애물 순위 재부여)")
    void parse_success() {
        ReadingDraft draft = read(validJson(MYSTERY, "RELATIONSHIP_RECONSIDERATION"), List.of());

        assertThat(draft.coverVerdict()).contains("다시 판단하는 상태");
        assertThat(draft.mysteries()).hasSize(1);
        assertThat(draft.mysteries().get(0).covers()).containsExactly("NOW", "RESOLVE");
        assertThat(draft.mysteries().get(0).principle()).contains("먼저 알리는 방식");
        // 모델이 보낸 rank(7,7)는 버리고 배열 순서로 1,2를 다시 매긴다
        assertThat(draft.blockers()).extracting(ReadingDraft.Blocker::rank).containsExactly(1, 2);
        assertThat(draft.reselect().conditions()).containsExactly("안전한 대화 경험");
        assertThat(draft.phase().chipSeeds()).containsExactly("먼저 연락해도 될까?");
        assertThat(draft.internal().nowState()).isEqualTo("RELATIONSHIP_RECONSIDERATION");
    }

    @Test
    @DisplayName("미스터리가 하나도 없으면 스토리 리포트가 아니다 — 판독 실패(판정은 유지)")
    void parse_noMysteries_throws() {
        assertThatThrownBy(() -> read(validJson("", "MIXED"), List.of()))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.threeam.llm.LlmException.class);
    }

    @Test
    @DisplayName("사전 밖 state는 버리고 대체값으로 채운다 (salvage 경로 방어)")
    void parse_unknownState_fallsBack() {
        ReadingDraft draft = read(validJson(MYSTERY, "WEIRD"), List.of());

        assertThat(draft.internal().nowState()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("readingFacts가 비면 요인 근거를 관찰 사실로 승격해 payload에 싣는다(안전망)")
    void payload_fallbackFactsFromFactors() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(MYSTERY, "MIXED")));
        new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(saved(), diagnosis(List.of()), null, List.of(), "MID", null).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String payload = captor.getValue().get(1).content();
        assertThat(payload).contains("\"F01\"").contains("두 달째 무반응");
        assertThat(payload).contains("다시 연락이 올까요?"); // directQuestions 전달
    }
}
