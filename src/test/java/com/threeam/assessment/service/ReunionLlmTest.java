package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
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
                  "breakupType": "CLINGER",
                  "partnerType": "COLD",
                  "deductions": [
                    {"signal": "차단", "points": 30, "evidence": "차단당함"},
                    {"signal": "무시할 값", "points": 0, "evidence": "버려짐"}
                  ],
                  "reason": "쉽지 않아",
                  "summary": "상대가 차단함"
                }
                """;
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.breakupType()).isEqualTo(BreakupType.CLINGER);
        assertThat(diagnosis.partnerType()).isEqualTo(PartnerType.COLD);
        assertThat(diagnosis.deductions()).hasSize(1); // points=0 항목은 버려진다
        assertThat(diagnosis.deductions().get(0).points()).isEqualTo(30);
        assertThat(diagnosis.summary()).isEqualTo("상대가 차단함");
    }

    @Test
    @DisplayName("newFacts를 파싱한다 — 빈 문자열은 버리고, 정상 범위의 개수는 자르지 않는다")
    void parse_newFacts() {
        String json = """
                {"verdict": "POSSIBLE", "deductions": [], "reason": "", "summary": "",
                 "newFacts": ["사실1", "", "사실2", "사실3", "사실4", "사실5", "사실6"]}
                """;
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(json));

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
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.newFacts()).hasSize(20).startsWith("사실1").endsWith("사실20");
    }

    @Test
    @DisplayName("알 수 없는 enum 값은 안전한 기본값으로 떨어진다")
    void parse_unknownEnum_fallsBack() {
        String json = """
                {"verdict": "???", "breakupType": "WAT", "partnerType": null,
                 "deductions": [], "reason": "", "summary": ""}
                """;
        given(llmClient.generateJson(anyList())).willReturn(CompletableFuture.completedFuture(json));

        ReunionDiagnosis diagnosis = reunionLlm().diagnose(null, List.of(), List.of()).join();

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE); // 기본값
        assertThat(diagnosis.breakupType()).isNull();
        assertThat(diagnosis.partnerType()).isNull();
    }

    @Test
    @DisplayName("깨진 JSON은 LlmException으로 실패한다")
    void parse_malformed_throws() {
        given(llmClient.generateJson(anyList()))
                .willReturn(CompletableFuture.completedFuture("이건 JSON이 아니야"));

        assertThatThrownBy(() -> reunionLlm().diagnose(null, List.of(), List.of()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LlmException.class);
    }
}
