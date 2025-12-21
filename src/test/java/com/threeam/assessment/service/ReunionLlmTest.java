package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.ReunionVerdict;
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
class ReunionLlmTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReunionLlm reunionLlm() {
        return new ReunionLlm(llmClient, objectMapper);
    }

    @Test
    @DisplayName("정상 JSON을 진단으로 파싱한다 (verdict/타입/감점/요약)")
    void parse_success() {
        String json = """
                {
                  "verdict": "POSSIBLE",
                  "myAttachment": "ANXIOUS",
                  "partnerAttachment": "AVOIDANT",
                  "activeReunionOffer": true,
                  "deductions": [
                    {"signal": "차단", "axis": "마음", "points": 30, "evidence": "차단당함"},
                    {"signal": "무시할 값", "axis": "마음", "points": 0, "evidence": "버려짐"}
                  ],
                  "reason": "쉽지 않아",
                  "summary": "상대가 차단함"
                }
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.myAttachment()).isEqualTo(AttachmentStyle.ANXIOUS);
        assertThat(diagnosis.partnerAttachment()).isEqualTo(AttachmentStyle.AVOIDANT);
        assertThat(diagnosis.activeReunionOffer()).isTrue();
        assertThat(diagnosis.deductions()).hasSize(1); // points=0 항목은 버려진다
        assertThat(diagnosis.deductions().get(0).points()).isEqualTo(30);
        assertThat(diagnosis.summary()).isEqualTo("상대가 차단함");
    }

    @Test
    @DisplayName("판단 축(axis)이 없거나 세 축이 아닌 항목은 도덕 채점으로 보고 버린다")
    void parse_dropsItemsWithoutValidAxis() {
        String json = """
                {
                  "verdict": "POSSIBLE",
                  "breakupType": "REGRETTER",
                  "partnerType": "AMBIVALENT",
                  "deductions": [
                    {"signal": "이별 후 문란한 사생활", "points": 20, "evidence": "클럽 방문"},
                    {"signal": "무책임한 인성", "axis": "도덕", "points": 10, "evidence": "근거"},
                    {"signal": "상대가 지쳐서 떠남", "axis": "마음", "points": 20, "evidence": "근거"}
                  ],
                  "boosts": [
                    {"signal": "먼저 안부 연락", "points": 5, "evidence": "축 없음 - 버려짐"}
                  ],
                  "reason": "", "summary": ""
                }
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.deductions()).hasSize(1);
        assertThat(diagnosis.deductions().get(0).signal()).isEqualTo("상대가 지쳐서 떠남");
        assertThat(diagnosis.boosts()).isEmpty();
    }

    @Test
    @DisplayName("신호가 있었는데 축이 없어 전부 폐기되면 근거 없는 70%를 막으려 INSUFFICIENT로 강등한다")
    void parse_allSignalsDropped_downgradesToInsufficient() {
        String json = """
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "deductions": [
                    {"signal": "이별 후 문란한 사생활", "points": 20, "evidence": "축 없음"},
                    {"signal": "무책임한 인성", "axis": "도덕", "points": 10, "evidence": "잘못된 축"}
                  ],
                  "boosts": [],
                  "reason": "", "summary": ""
                }
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        // 남은 감점이 0인데 BASE(70)를 그대로 확률로 내보내지 않는다.
        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(diagnosis.deductions()).isEmpty();
    }

    @Test
    @DisplayName("활성 재회 제안이면 감점 신호가 전부 폐기돼도 강등하지 않는다(확률은 100으로 확정될 경로)")
    void parse_allDroppedButActiveOffer_staysPossible() {
        String json = """
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": true,
                  "deductions": [
                    {"signal": "문란한 사생활", "points": 20, "evidence": "축 없음"}
                  ],
                  "boosts": [],
                  "reason": "", "summary": ""
                }
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.activeReunionOffer()).isTrue();
    }

    @Test
    @DisplayName("points는 크기만 보고 상한(100)으로 자른다 — 음수, 폭주값 방어")
    void parse_pointsClampedAndAbs() {
        String json = """
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "deductions": [
                    {"signal": "폭주값", "axis": "마음", "points": 9999, "evidence": "근거"},
                    {"signal": "음수값", "axis": "구조", "points": -15, "evidence": "근거"}
                  ],
                  "boosts": [],
                  "reason": "", "summary": ""
                }
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.deductions()).hasSize(2);
        assertThat(diagnosis.deductions().get(0).points()).isEqualTo(100); // 9999 → 상한 100
        assertThat(diagnosis.deductions().get(1).points()).isEqualTo(15);  // -15 → 크기 15
    }

    @Test
    @DisplayName("newFacts를 파싱한다 — 빈 문자열은 버리고, 정상 범위의 개수는 자르지 않는다")
    void parse_newFacts() {
        String json = """
                {"verdict": "POSSIBLE", "deductions": [], "reason": "", "summary": "",
                 "newFacts": ["사실1", "", "사실2", "사실3", "사실4", "사실5", "사실6"]}
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        // 중요한 사실을 개수로 자르지 않는다(빈 문자열만 제거) — 원장 무상한 정책과 한 몸
        assertThat(diagnosis.newFacts())
                .containsExactly("사실1", "사실2", "사실3", "사실4", "사실5", "사실6");
    }

    @Test
    @DisplayName("newFacts 폭주 방어 — 안전핀(20개)을 넘는 이상 응답만 잘라낸다")
    void parse_newFacts_runawayCapped() {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            items.append(i > 1 ? "," : "").append("\"사실").append(i).append("\"");
        }
        String json = "{\"verdict\": \"POSSIBLE\", \"deductions\": [], \"reason\": \"\", \"summary\": \"\","
                + " \"newFacts\": [" + items + "]}";
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.newFacts()).hasSize(20).startsWith("사실1").endsWith("사실20");
    }

    @Test
    @DisplayName("알 수 없는 enum 값은 안전한 기본값으로 떨어진다")
    void parse_unknownEnum_fallsBack() {
        String json = """
                {"verdict": "???", "myAttachment": "WAT", "partnerAttachment": null,
                 "deductions": [], "reason": "", "summary": ""}
                """;
        given(llmClient.generateJsonDeep(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE); // 기본값
        assertThat(diagnosis.myAttachment()).isNull();
        assertThat(diagnosis.partnerAttachment()).isNull();
        assertThat(diagnosis.activeReunionOffer()).isFalse(); // 필드 누락 시 안전한 기본값
    }

    @Test
    @DisplayName("깨진 JSON은 LlmException으로 실패한다")
    void parse_malformed_throws() {
        given(llmClient.generateJsonDeep(anyList()))
                .willReturn(CompletableFuture.completedFuture("이건 JSON이 아니야"));

        assertThatThrownBy(() -> reunionLlm().diagnose(null, List.of(), List.of()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LlmException.class);
    }
}
