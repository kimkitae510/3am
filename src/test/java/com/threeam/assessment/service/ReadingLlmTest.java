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
                .read(saved(), diagnosis(facts), null, "MID", List.of()).join();
    }

    // 유효한 v7(진단 우선) JSON 뼈대. 테스트마다 일부만 바꿔 쓴다.
    private static String validJson(String diagnosis, String nowState) {
        return """
                {
                  "diagnosisSummary": "이별 후 상대의 태도가 닫히지 않아 다시 판단 중인 판으로 읽힙니다.",
                  "diagnosis": [%s],
                  "analysisChapters": [
                    {"eyebrow": "먼저 풀어야 할 모순", "title": "미래를 말한 다음날 왜 물러났을까?", "chapterRole": "CORE_CONTRADICTION", "interpretationId": "U01", "answer": "상처 때문에 가깝습니다.", "reading": "방향이 하루 만에 꺾인 것은 충격의 크기를 말합니다.", "psychology": {"concept": "정서적 안전감 확인", "reading": "사랑 확인으로 안전감을 얻으려 했습니다."}, "repairPrinciple": "감정의 이유를 먼저 알리는 방식이 필요합니다.", "evidenceIds": ["F01"]}
                  ],
                  "maintenanceInsight": null,
                  "reselect": {"title": "무엇이 일어나면 판단이 바뀔까?", "answer": "관계 대화가 다시 열리는지가 첫 분기점입니다.", "reading": "주체보다 대화의 내용이 판을 가릅니다.", "turningPoints": ["관계 이야기를 다시 꺼내는지"]},
                  "final": {"stateLabel": "관계 재평가 중", "chipSeeds": ["먼저 연락해도 될까?"]},
                  "internal": {"nowState": "%s", "resolveState": "UNSTABLE", "remainState": "PRESENT", "reselectState": "CONDITIONAL"}
                }
                """.formatted(diagnosis, nowState);
    }

    // rank를 일부러 어긋나게(7, 7) 보내 배열 순서로 다시 매기는지 본다.
    private static final String DIAGNOSIS = """
            {"key": "partnerSignal", "label": "상대신호", "rank": 7, "impact": "UP", "verdict": "문을 완전히 닫지는 않았습니다.", "reading": "종료 의사를 직접 밝힌 적은 없습니다.", "evidenceIds": ["F01"]},
            {"key": "breakupReason", "label": "이별사유", "rank": 7, "impact": "STRONG_DOWN", "verdict": "지쳐서 끝난 이별입니다.", "reading": "누적된 피로가 크게 작용했습니다.", "evidenceIds": []}
            """;

    @Test
    @DisplayName("정상 JSON을 v7 리포트로 파싱한다 (진단 요약, 진단 항목, 심층 장, 마지막 화면)")
    void parse_success() {
        ReadingDraft draft = read(validJson(DIAGNOSIS, "RELATIONSHIP_RECONSIDERATION"), List.of());

        assertThat(draft.diagnosisSummary()).contains("다시 판단 중");
        assertThat(draft.diagnosis()).hasSize(2);
        assertThat(draft.diagnosis().get(0).key()).isEqualTo("partnerSignal");
        assertThat(draft.diagnosis().get(0).impact()).isEqualTo("UP");
        assertThat(draft.diagnosis().get(1).impact()).isEqualTo("STRONG_DOWN");
        // 모델이 보낸 rank(7,7)는 버리고 배열 순서로 1,2를 다시 매긴다
        assertThat(draft.diagnosis()).extracting(ReadingDraft.Diagnosis::rank)
                .containsExactly(1, 2);
        assertThat(draft.analysisChapters()).hasSize(1);
        assertThat(draft.analysisChapters().get(0).eyebrow()).isEqualTo("먼저 풀어야 할 모순");
        assertThat(draft.analysisChapters().get(0).interpretationId()).isEqualTo("U01");
        assertThat(draft.analysisChapters().get(0).psychology().concept()).isEqualTo("정서적 안전감 확인");
        assertThat(draft.maintenanceInsight()).isNull();
        assertThat(draft.reselect().turningPoints()).containsExactly("관계 이야기를 다시 꺼내는지");
        assertThat(draft.fin().stateLabel()).isEqualTo("관계 재평가 중");
        assertThat(draft.internal().nowState()).isEqualTo("RELATIONSHIP_RECONSIDERATION");
    }

    @Test
    @DisplayName("진단 항목이 하나도 없으면 v7 리포트가 아니다 — 판독 실패(판정은 유지)")
    void parse_noDiagnosis_throws() {
        assertThatThrownBy(() -> read(validJson("", "MIXED"), List.of()))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.threeam.llm.LlmException.class);
    }

    @Test
    @DisplayName("사전 밖 진단 키와 영향값은 걸러낸다 (salvage 경로 방어)")
    void parse_unknownDiagnosisVocab_filtered() {
        ReadingDraft draft = read(validJson("""
                {"key": "엉뚱한키", "label": "엉뚱", "rank": 1, "impact": "UP", "verdict": "답", "reading": "서술", "evidenceIds": []},
                {"key": "replacement", "label": "대체자", "rank": 2, "impact": "WEIRD", "verdict": "답", "reading": "서술", "evidenceIds": []}
                """, "MIXED"), List.of());

        assertThat(draft.diagnosis()).hasSize(1);
        assertThat(draft.diagnosis().get(0).key()).isEqualTo("replacement");
        assertThat(draft.diagnosis().get(0).impact()).isEqualTo("NEUTRAL"); // 어휘 밖은 중립으로
    }

    @Test
    @DisplayName("사전 밖 state는 버리고 대체값으로 채운다")
    void parse_unknownState_fallsBack() {
        ReadingDraft draft = read(validJson(DIAGNOSIS, "WEIRD"), List.of());

        assertThat(draft.internal().nowState()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("readingFacts가 비면 요인 근거를 관찰 사실로 승격해 packet에 싣는다(안전망)")
    void packet_fallbackFactsFromFactors() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DIAGNOSIS, "MIXED")));
        new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(saved(), diagnosis(List.of()), null, "MID", List.of()).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String packet = captor.getValue().get(1).content();
        assertThat(packet).contains("\"F01\"").contains("두 달째 무반응");
        assertThat(packet).contains("다시 연락이 올까요?"); // directQuestions 전달
        // 요인표와 관계심리 판정값은 안 실린다 — 실리면 2호출이 요인표를 복창한다
        assertThat(packet).doesNotContain("factors").doesNotContain("relationshipPsychology");
    }
}
