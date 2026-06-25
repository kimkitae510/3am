package com.threeam.llm;

import com.threeam.assessment.service.ReadingLlm;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 실제 LLM 연동 전까지 사용하는 스텁. 고정 응답을 즉시 완료된 future로 돌려주어 API 키, 비용 없이 전체 흐름을 검증한다.
// 실 구현(Gemini)은 llm.provider=gemini 로 두고 별도 빈으로 갈아끼운다.
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return CompletableFuture.completedFuture(
                "지금은 많이 힘든 시간일 겁니다. 여기서는 천천히, 하고 싶은 만큼 이야기하셔도 괜찮습니다. "
                        + "(개발용 임시 응답 — 실제 LLM 연동 전 고정 메시지입니다.)");
    }

    // 정밀 판독 호출(판정 블록이 실린 프롬프트)만 갈라 판독용 고정 JSON을 돌려준다 —
    // 책 모드 화면과 저장 흐름을 키, 비용 없이 검증하기 위한 분기.
    @Override
    public CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages,
                                                      Map<String, Object> responseSchema) {
        boolean readingCall = messages.stream()
                .anyMatch(m -> m.role() == LlmRole.SYSTEM
                        && m.content().startsWith(ReadingLlm.VERDICT_BLOCK_HEADER));
        if (readingCall) {
            return CompletableFuture.completedFuture("""
                    {
                      "overall": "(개발용 임시) 마음이 사라져 관계를 정리한 상태라기보다, 지친 끝에 물러나 이 관계를 계속할 수 있을지 다시 판단하는 상태에 가깝습니다.",
                      "coverRaise": "(개발용 임시) 통보 직전까지 관계를 붙잡는 표현이 있었습니다.",
                      "coverBlock": "(개발용 임시) 지치게 한 문제가 아직 그대로 남아 있습니다.",
                      "now": {"state": "DETACHED", "answer": "(개발용 임시) 지금은 거리를 두고 감정을 추스르는 쪽에 가깝습니다.", "reading": "(개발용 임시) 두 달째 무반응은 분노보다 소진의 신호로 읽힙니다. 다만 무반응 하나로 마음이 끝났다고까지 볼 근거는 부족합니다."},
                      "resolve": {"state": "MODERATE", "answer": "(개발용 임시) 충동은 아니지만 행동까지 굳은 결심도 아닙니다.", "reading": "(개발용 임시) 통보는 다툼 한복판이 아니라 지친 끝에 나왔습니다."},
                      "remain": {"state": "PRESENT", "answer": "(개발용 임시) 감정이 완전히 정리된 근거는 없습니다. 다만 마음이 남은 것과 다시 만나겠다는 선택은 다릅니다.", "reading": "(개발용 임시) 관계를 부정하는 행동은 관찰되지 않았지만, 관계를 다시 선택한 행동도 아직 없습니다."},
                      "drift": "(개발용 임시) 이번 갈등에서는 확인과 거리두기에 가까운 상호작용이 한 번 강하게 나타났습니다. 한쪽이 불안해져 관계를 확인하면 다른 쪽이 지쳐 물러났고, 그 거리감이 다시 불안을 키웠습니다. 다만 이전에 반복된 근거는 없어 고정된 패턴이라고 단정할 단계는 아닙니다.",
                      "blocking": "(개발용 임시) 지금 가장 큰 장애물은 마음의 유무보다, 다시 관계 안에서 같은 문제가 반복되지 않을 거라고 느낄 수 있느냐에 가깝습니다. 지치게 한 현실 문제도 아직 남아 있습니다.",
                      "reselect": {"state": "CONDITIONAL", "answer": "(개발용 임시) 다시 선택할 여지가 남아 있습니다. 다만 그 여지가 실제 재회로 바뀌려면 상대가 관계 대화를 다시 선택해야 합니다.", "open": "(개발용 임시) 감정이 끊어진 근거는 없고 대화 가능성이 남아 있습니다.", "route": "(개발용 임시) 거리두기 이후 상대가 갈등이 이전과 다르게 다뤄질 수 있다고 느끼는 경우입니다."},
                      "phase": "(개발용 임시) 지금은 설득할 때가 아니라 상대의 거리두기 이후 선택을 확인할 단계입니다.",
                      "nowTitle": "(개발용 임시) 상대는 지금 무슨 생각일까",
                      "resolveTitle": "(개발용 임시) 정말 끝낼 결심이었을까",
                      "remainTitle": "(개발용 임시) 마음은 남아 있을까",
                      "driftTitle": "(개발용 임시) 마음이 있는데 왜 멀어졌을까",
                      "blockingTitle": "(개발용 임시) 지금 재회를 막고 있는 건 뭘까",
                      "routeTitle": "(개발용 임시) 무엇이 바뀌면 다시 움직일까"
                    }
                    """);
        }
        return generateJson(messages);
    }

    // 분석 흐름 검증용 고정 JSON. 실제 판단은 Gemini가 한다.
    // 유저 발화가 적으면 INSUFFICIENT(데이터 부족)를, 충분하면 POSSIBLE을 돌려줘 두 흐름을 다 확인할 수 있게 한다.
    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        long userTurns = messages.stream().filter(m -> m.role() == LlmRole.USER).count();
        if (userTurns < 3) {
            return CompletableFuture.completedFuture("""
                    {
                      "verdict": "INSUFFICIENT",
                      "reason": "아직 분석하기엔 이야기가 부족합니다. 어쩌다 헤어졌는지, 지금 연락은 되는지, 상대와 최근 있었던 일을 조금만 더 들려주십시오.",
                      "summary": ""
                    }
                    """);
        }
        return CompletableFuture.completedFuture("""
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "breakupType": "소진형",
                  "typeEvidence": "(개발용 임시) 반복된 다툼 끝에 상대가 지쳐 통보",
                  "userDumpedPartnerLingering": false,
                  "factors": [
                    {"name": "상대신호", "level": "불리", "evidence": "(개발용 임시 근거)", "rationale": "(개발용 임시) 두 달째 무반응이 이어져 불리"},
                    {"name": "대체자", "level": "중립", "evidence": "근거 없음", "rationale": null},
                    {"name": "유저대처", "level": "유리", "evidence": "(개발용 임시 근거)", "rationale": "(개발용 임시) 짧게 마무리하고 연락을 멈춰 유리"},
                    {"name": "통보온도", "level": "중립", "evidence": "근거 없음", "rationale": null},
                    {"name": "상대패턴", "level": "중립", "evidence": "근거 없음", "rationale": null}
                  ],
                  "relapseRisk": {"level": "높음", "reason": "(개발용 임시) 지치게 한 행동의 교정이 확인되지 않음"},
                  "watchFor": [
                    {"point": "상대가 먼저 연락해 오는지", "effect": "오면 상대신호가 유리로 바뀌어 판이 크게 달라짐"}
                  ],
                  "matchProfile": {
                    "reason": "잦은싸움",
                    "subReasons": ["사소한반복", "감정누적"],
                    "dumper": "상대",
                    "fault": "양쪽",
                    "contactState": "무연락",
                    "monthsSinceBreakup": 2,
                    "datingMonths": 18,
                    "ageGroup": null,
                    "gender": null,
                    "repeatBreakup": null,
                    "partnerHasNew": null
                  },
                  "reason": "개발용 임시 분석 — 실제 LLM 연동 전 고정 응답입니다.",
                  "newFacts": ["상대가 먼저 이별을 통보함 (개발용 임시 사실)"]
                }
                """);
    }
}
