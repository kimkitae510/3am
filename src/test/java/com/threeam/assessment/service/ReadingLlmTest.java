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
        ReunionDiagnosis.MatchProfileItem profile = new ReunionDiagnosis.MatchProfileItem(
                null, List.of(), "상대", null, null, null, null, null, null, null, null);
        ReunionDiagnosis.DisplayDiagnosis display = new ReunionDiagnosis.DisplayDiagnosis(
                "이별 후 상대의 태도가 닫히지 않아 다시 판단 중인 판입니다.",
                new ReunionDiagnosis.TimeInsight("시간효과", "지금은 식힐수록 유리한 구간입니다.",
                        "지친 이별이라 자극이 줄면 피로 자체가 옅어집니다."),
                List.of(new ReunionDiagnosis.DisplayItem("partnerSignal", "상대신호",
                        "FACTOR:상대신호", "문을 완전히 닫지는 않았습니다.",
                        "종료 의사를 직접 밝힌 적은 없습니다.", List.of(1))));
        return new ReunionDiagnosis(ReunionVerdict.POSSIBLE, false, null, null, JumpRule.NONE,
                List.of(), null, null, List.of(), List.of(), profile, null, "총평", List.of(),
                facts, List.of("다시 연락이 올까요?"), List.of(),
                new ReunionDiagnosis.TimeEffect("COOLING_HELPFUL", "2주", "SHORT_COOLING_THEN_RECONTACT",
                        "지친 이별이라 자극이 줄면 회복된다"),
                display);
    }

    // 진단 문장은 1호출이 쓰고 백엔드가 순위와 등급을 붙인 카드다 — 판독은 못 고친다.
    private static final List<ReadingLlm.DiagnosisCard> CARDS = List.of(
            new ReadingLlm.DiagnosisCard("partnerSignal", "상대신호", "CORE", 1, "유리",
                    "CONFIRMED", "문을 완전히 닫지는 않았습니다.", "종료 의사를 직접 밝힌 적은 없습니다.",
                    List.of("F01")),
            new ReadingLlm.DiagnosisCard("breakupReason", "이별사유", "CORE", 2, "매우불리",
                    "CONFIRMED", "지쳐서 끝난 이별입니다.", "누적된 피로가 크게 작용했습니다.",
                    List.of()));

    private ReadingDraft read(String json, List<ReunionDiagnosis.ReadingFact> facts) {
        return read(json, facts, CARDS);
    }

    private ReadingDraft read(String json, List<ReunionDiagnosis.ReadingFact> facts,
                              List<ReadingLlm.DiagnosisCard> cards) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(json));
        return new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(saved(), diagnosis(facts), null, "MID", cards).join();
    }

    // 유효한 v11.1 JSON 뼈대. 진단 배열은 서버가 덮으므로 일부러 엉뚱한 값을 넣어 둔다.
    private static String validJson(String chapters, String nowState) {
        return """
                {
                  "diagnosisSummary": "모델이 고쳐 쓴 요약",
                  "diagnosis": [
                    {"key": "partnerSignal", "label": "상대신호", "group": "EXTRA", "rank": 9, "level": "매우불리", "evidenceState": "PARTIAL", "headline": "모델이 고쳐 쓴 판정", "reading": "모델이 고쳐 쓴 근거", "factIds": []}
                  ],
                  "analysisSection": {"title": "이번 이별, 뭐가 문제였을까?"},
                  "analysisChapters": [%s],
                  "actionPlan": {
                    "title": "지금은 어떻게 움직이는 게 나을까?",
                    "stance": "USE_EXISTING_EVENT",
                    "answer": "이미 잡힌 만남을 그대로 씁니다.",
                    "timing": "2주 뒤 예정된 만남",
                    "whyThisTiming": "새 명분을 만들지 않아도 됩니다.",
                    "goal": "관계 대화의 여지 확인",
                    "do": ["설득보다 태도로 보여준다"],
                    "stopCondition": "관계 이야기를 피하면 더 밀지 않는다",
                    "avoid": ["장문 메시지"]
                  },
                  "chipSeeds": ["만나면 무슨 말부터 해야 할까?"],
                  "internal": {"nowState": "%s", "resolveState": "UNSTABLE", "remainState": "PRESENT", "reselectState": "CONDITIONAL"}
                }
                """.formatted(chapters, nowState);
    }

    private static final String CHAPTER = """
            {"eyebrow": "먼저 풀어야 할 모순", "title": "미래를 말한 다음날 왜 물러났을까?", "chapterRole": "CORE_CONTRADICTION", "interpretationId": "U01", "answer": "상처 때문에 가깝습니다.", "reading": "방향이 하루 만에 꺾인 것은 충격의 크기를 말합니다.", "psychology": {"concept": "정서적 안전감 확인", "reading": "사랑 확인으로 안전감을 얻으려 했습니다."}, "repairPrinciple": "감정의 이유를 먼저 알리는 방식이 필요합니다.", "evidenceIds": ["F01"]}
            """;

    @Test
    @DisplayName("진단 문장은 1호출 카드로 덮는다 — 판독이 고쳐 써도 화면엔 안 나간다")
    void parse_diagnosisMergedFromCards() {
        ReadingDraft draft = read(validJson(CHAPTER, "RELATIONSHIP_RECONSIDERATION"), List.of());

        assertThat(draft.diagnosisSummary()).isEqualTo("이별 후 상대의 태도가 닫히지 않아 다시 판단 중인 판입니다.");
        assertThat(draft.diagnosis()).hasSize(2);
        assertThat(draft.diagnosis().get(0).headline()).isEqualTo("문을 완전히 닫지는 않았습니다.");
        assertThat(draft.diagnosis().get(0).level()).isEqualTo("유리");   // 모델은 매우불리로 보냈다
        assertThat(draft.diagnosis().get(0).group()).isEqualTo("CORE");   // 모델은 EXTRA로 보냈다
        assertThat(draft.diagnosis().get(0).rank()).isEqualTo(1);          // 모델은 9로 보냈다
        assertThat(draft.timeInsight().headline()).contains("식힐수록");
    }

    @Test
    @DisplayName("행동 계획을 파싱한다 (자세, 시점, 멈출 조건)")
    void parse_actionPlan() {
        ReadingDraft draft = read(validJson(CHAPTER, "MIXED"), List.of());

        assertThat(draft.actionPlan().stance()).isEqualTo("USE_EXISTING_EVENT");
        assertThat(draft.actionPlan().timing()).isEqualTo("2주 뒤 예정된 만남");
        assertThat(draft.actionPlan().stopCondition()).contains("더 밀지 않는다");
        assertThat(draft.actionPlan().doList()).containsExactly("설득보다 태도로 보여준다");
        assertThat(draft.analysisSectionTitle()).isEqualTo("이번 이별, 뭐가 문제였을까?");
        assertThat(draft.chipSeeds()).containsExactly("만나면 무슨 말부터 해야 할까?");
    }

    @Test
    @DisplayName("진단 카드가 없으면 리포트가 아니다 — 판독 실패(판정은 유지)")
    void parse_noCards_throws() {
        assertThatThrownBy(() -> read(validJson(CHAPTER, "MIXED"), List.of(), List.of()))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.threeam.llm.LlmException.class);
    }

    @Test
    @DisplayName("사전 밖 state와 자세는 대체값으로 채운다 (salvage 경로 방어)")
    void parse_unknownVocab_fallsBack() {
        ReadingDraft draft = read(validJson(CHAPTER, "WEIRD"), List.of());

        assertThat(draft.internal().nowState()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("readingFacts가 비면 요인 근거를 관찰 사실로 승격해 packet에 싣는다(안전망)")
    void packet_fallbackFactsFromFactors() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(CHAPTER, "MIXED")));
        new ReadingLlm(llmClient, objectMapper, new ReadingProperties())
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String packet = captor.getValue().get(1).content();
        assertThat(packet).contains("\"F01\"").contains("두 달째 무반응");
        assertThat(packet).contains("다시 연락이 올까요?");        // directQuestions 전달
        assertThat(packet).contains("breakupDeclaredBy").contains("상대");
        assertThat(packet).contains("timeEffect").contains("COOLING_HELPFUL");
        // 요인표와 관계심리 판정값은 안 실린다 — 실리면 2호출이 요인표를 복창한다
        assertThat(packet).doesNotContain("relationshipPsychology");
    }
}
