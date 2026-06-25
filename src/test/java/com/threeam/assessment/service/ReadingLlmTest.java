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
    private static String validJson(String nowState, String nowAnswer, String routeTitle) {
        return """
                {
                  "overall": "마음이 끝나 정리한 상태보다, 상처 뒤에 다시 판단하는 상태에 가깝다.",
                  "coverRaise": "직전까지 관계를 붙잡는 표현이 있었다.",
                  "coverBlock": "마지막 대화의 상처가 크고 현실 문제가 남아 있다.",
                  "now": {"state": "%s", "answer": "%s", "reading": "무슨 말을 해야 할지 모르겠다는 말이 울음보다 정보 가치가 높다"},
                  "resolve": {"state": "UNSTABLE", "answer": "끝내기로 한 결심이라 보기 어렵다", "reading": "갈등 직후의 요청이고 명확한 통보가 아니다"},
                  "remain": {"state": "PRESENT", "answer": "애정 잔존의 근거는 꽤 강하다", "reading": "다만 마음이 남은 것과 재선택은 다르다"},
                  "drift": "확인과 거리두기에 가까운 상호작용이 한 번 강하게 나타났다. 반복 근거는 없어 고정 패턴이라 단정할 단계는 아니다.",
                  "blocking": "가장 큰 장애물은 마음의 유무보다 다시 안전하게 이야기할 수 있느냐다. 현실 문제도 남아 있다.",
                  "reselect": {"state": "CONDITIONAL", "answer": "여지가 남아 있고, 거리두기 이후의 선택에서 갈린다", "open": "직전까지 회복 표현이 있었다", "route": "거리두기 이후 상대가 관계 대화를 다시 선택하는 경우"},
                  "phase": "설득할 시점이 아니라 상대의 선택을 확인할 구간이다.",
                  "nowTitle": "상대는 지금 무슨 생각일까",
                  "resolveTitle": "2주는 정말 이별 준비였을까",
                  "remainTitle": "마음은 남아 있을까",
                  "driftTitle": "마음이 있는데 왜 멀어졌을까",
                  "blockingTitle": "지금 재회를 막는 건 뭘까",
                  "routeTitle": "%s"
                }
                """.formatted(nowState, nowAnswer, routeTitle);
    }

    @Test
    @DisplayName("정상 JSON을 판독으로 파싱한다 (표지, 여섯 장, 장 제목)")
    void parse_success() {
        ReadingDraft draft = read(validJson("RELATIONSHIP_RECONSIDERATION",
                "다시 판단하기 위해 물러난 상태에 가깝다", "무엇이 바뀌면 다시 움직일까"));

        assertThat(draft.overall()).contains("다시 판단하는 상태");
        assertThat(draft.coverRaise()).contains("붙잡는 표현");
        assertThat(draft.coverBlock()).contains("상처");
        assertThat(draft.now().state()).isEqualTo("RELATIONSHIP_RECONSIDERATION");
        assertThat(draft.resolve().state()).isEqualTo("UNSTABLE");
        assertThat(draft.remain().reading()).contains("재선택은 다르다");
        assertThat(draft.drift()).contains("단정할 단계는 아니다");
        assertThat(draft.blocking()).contains("장애물");
        assertThat(draft.reselect().route()).contains("다시 선택하는 경우");
        assertThat(draft.phase()).contains("확인할 구간");
        assertThat(draft.chapterTitles())
                .containsEntry("route", "무엇이 바뀌면 다시 움직일까")
                .containsKeys("now", "resolve", "remain", "drift", "blocking");
    }

    @Test
    @DisplayName("사전 밖 state는 버리고 대체값으로 채운다 (salvage 경로 방어)")
    void parse_unknownState_fallsBack() {
        ReadingDraft draft = read(validJson("WEIRD_STATE", "답", "훅"));

        assertThat(draft.now().state()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("장 제목이 비면 고정 제목으로 폴백한다")
    void parse_blankTitle_fallsBack() {
        ReadingDraft draft = read(validJson("DETACHED", "답", ""));

        assertThat(draft.chapterTitles().get("route")).isEqualTo("무엇이 바뀌면 다시 움직일까");
    }

    @Test
    @DisplayName("답이 비어 있으면 판독으로 성립하지 않는다 — LlmException")
    void parse_blankAnswer_throws() {
        assertThatThrownBy(() -> read(validJson("DETACHED", "", "훅")))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LlmException.class);
    }
}
