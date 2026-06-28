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

    // 정밀 판독 호출(payload 블록이 실린 프롬프트)만 갈라 스토리북 고정 JSON을 돌려준다 —
    // 리포트 화면과 저장 흐름을 키, 비용 없이 검증하기 위한 분기.
    @Override
    public CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages,
                                                      Map<String, Object> responseSchema) {
        boolean readingCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.PAYLOAD_HEADER));
        if (readingCall) {
            return CompletableFuture.completedFuture("""
                    {
                      "probabilityReading": {"reading": "(개발용 임시) 이번 거리두기는 갈등 직후의 정서적 철수에 가깝습니다. 직전까지 관계를 붙잡는 표현이 있었고, 종료 의사를 직접 밝힌 적은 없습니다. 그래서 지금 숫자는 마음이 끝난 판이 아니라 다시 판단 중인 판으로 읽은 결과입니다.", "evidenceIds": ["F01"]},
                      "chapters": [
                        {"eyebrow": "(개발용 임시) 먼저 풀어야 할 모순", "title": "(개발용 임시) 미래를 말한 다음날 왜 물러났을까?", "chapterRole": "CORE_CONTRADICTION", "answer": "(개발용 임시) 마음이 식어서가 아니라 마지막 대화의 상처 때문에 가깝습니다.", "reading": "(개발용 임시) 직전까지 관계를 다시 믿어보려는 방향으로 움직이고 있었습니다. 그 방향이 하루 만에 꺾인 것은 감정의 소멸보다 충격의 크기를 말해줍니다.", "psychology": null, "repairPrinciple": null, "evidenceIds": ["F01"]},
                        {"eyebrow": "(개발용 임시) 조금 과하게 해석하고 있을 수 있는 부분", "title": "(개발용 임시) 그 사건은 마음이 떠났다는 신호였을까?", "chapterRole": "SIGNAL_CORRECTION", "answer": "(개발용 임시) 현재 정보로는 그렇게 보기 어렵습니다.", "reading": "(개발용 임시) 이 사건에서 더 강한 정보는 기준의 차이가 만든 서운함입니다. 다만 제3자에게 마음이 갔다고 볼 별도의 행동은 확인되지 않았습니다.", "psychology": null, "repairPrinciple": null, "evidenceIds": ["F02"]},
                        {"eyebrow": "(개발용 임시) 오히려 더 중요하게 봐야 할 말", "title": "(개발용 임시) 냉랭함 뒤에 왜 사랑 확인이 나왔을까?", "chapterRole": "INTERACTION", "answer": "(개발용 임시) 관계가 흔들린다고 느껴 안전을 확인하려는 행동에 가깝습니다.", "reading": "(개발용 임시) 서로 상대가 했을 때 힘들어하던 방식을 이번 갈등에서는 반대 자리에서 경험했습니다. 반복 근거는 없어 고정 패턴이라 단정할 단계는 아닙니다.", "psychology": {"concept": "정서적 안전감 확인", "reading": "(개발용 임시) 한쪽은 차가워진 분위기에서 사랑을 확인해 안전감을 얻으려 했고, 다른 쪽은 문제를 정리해야 안정된다고 느꼈습니다."}, "repairPrinciple": "(개발용 임시) 불편한 감정의 존재와 이유를 먼저 언어화해 상대가 추측하게 두는 시간을 줄이는 방식이 필요합니다.", "evidenceIds": ["F03"]},
                        {"eyebrow": "(개발용 임시) 마음과 선택을 따로 봐야 하는 부분", "title": "(개발용 임시) 마음이 남았는데 왜 붙잡지 않을까?", "chapterRole": "FEELING_VS_CHOICE", "answer": "(개발용 임시) 감정이 남은 것과 다시 선택하는 것은 다른 문제이기 때문입니다.", "reading": "(개발용 임시) 애정의 근거는 남아 있지만, 관계 안으로 다시 들어가 대화하는 것에는 부담이 확인됩니다.", "psychology": null, "repairPrinciple": null, "evidenceIds": ["F03"]}
                      ],
                      "currentBarrier": {"answer": "(개발용 임시) 마지막 대화 이후 관계 대화 자체에 큰 부담을 느끼는 현재 상태입니다.", "reading": "(개발용 임시) 다시 이야기해도 또 상처받을지 모른다는 경계가 재선택을 막고 있습니다.", "evidenceIds": ["F03"]},
                      "secondaryBarrier": {"answer": "(개발용 임시) 해결되지 않은 현실 문제가 남아 있습니다.", "reading": "(개발용 임시) 납득과 별개로 현실 자체는 사라지지 않았습니다.", "evidenceIds": ["F02"]},
                      "maintenanceInsight": null,
                      "reselect": {"title": "(개발용 임시) 무엇이 일어나면 다시 관계가 움직일까?", "answer": "(개발용 임시) 거리두기 이후 관계 대화가 다시 열리는지가 첫 분기점입니다.", "reading": "(개발용 임시) 먼저 연락의 주체보다 관계 이야기를 다시 나눌 의사가 있는지가 판을 가릅니다.", "turningPoints": ["(개발용 임시) 기간이 끝난 뒤 관계 이야기를 다시 꺼내는지", "(개발용 임시) 마지막 갈등을 다시 다뤄보려 하는지"]},
                      "final": {"stateLabel": "(개발용 임시) 관계 재평가 중", "chipSeeds": ["(개발용 임시) 기간이 끝나면 먼저 연락해도 될까?", "(개발용 임시) 다시 대화할 때 무슨 말부터 해야 할까?"]},
                      "internal": {"nowState": "RELATIONSHIP_RECONSIDERATION", "resolveState": "UNSTABLE", "remainState": "PRESENT", "reselectState": "CONDITIONAL"}
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
