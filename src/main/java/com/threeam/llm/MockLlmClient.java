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
                      "diagnosisSummary": "(개발용 임시) 서버가 1호출 값으로 덮으므로 이 문장은 화면에 나가지 않습니다.",
                      "diagnosis": [
                        {"key": "partnerSignal", "label": "상대신호", "group": "CORE", "rank": 1, "level": "유리", "evidenceState": "CONFIRMED", "headline": "(개발용 임시) 복사값", "reading": "(개발용 임시) 복사값", "factIds": ["F01"]}
                      ],
                      "analysisSection": {"title": "(개발용 임시) 이번 이별, 뭐가 문제였을까?"},
                      "analysisChapters": [
                        {"eyebrow": "(개발용 임시) 먼저 풀어야 할 모순", "title": "(개발용 임시) 미래를 말한 다음날 왜 물러났을까?", "chapterRole": "CORE_CONTRADICTION", "interpretationId": null, "answer": "(개발용 임시) 마음이 식어서가 아니라 마지막 대화의 상처 때문에 가깝습니다.", "reading": "(개발용 임시) 직전까지 관계를 다시 믿어보려는 방향으로 움직이고 있었습니다. 그 방향이 하루 만에 꺾인 것은 감정의 소멸보다 충격의 크기를 말해줍니다.", "psychology": null, "repairPrinciple": null, "evidenceIds": ["F01"]},
                        {"eyebrow": "(개발용 임시) 조금 과하게 해석하고 있을 수 있는 부분", "title": "(개발용 임시) 그 사건은 마음이 떠났다는 신호였을까?", "chapterRole": "SIGNAL_CORRECTION", "interpretationId": "U01", "answer": "(개발용 임시) 현재 정보로는 그렇게 보기 어렵습니다.", "reading": "(개발용 임시) 이 사건에서 더 강한 정보는 기준의 차이가 만든 서운함입니다.", "psychology": {"concept": "정서적 안전감 확인", "reading": "(개발용 임시) 차가워진 분위기에서 관계가 안전한지 확인하려는 반응이 나타났습니다."}, "repairPrinciple": null, "evidenceIds": ["F02"]}
                      ],
                      "actionPlan": {
                        "title": "(개발용 임시) 지금은 어떻게 움직이는 게 나을까?",
                        "stance": "USE_EXISTING_EVENT",
                        "answer": "(개발용 임시) 이미 잡혀 있는 만남을 그대로 쓰는 것이 지금 할 수 있는 가장 자연스러운 접촉입니다.",
                        "timing": "(개발용 임시) 2주 뒤 예정된 만남",
                        "whyThisTiming": "(개발용 임시) 새 명분을 만들지 않아도 되고, 그 전에 연락을 밀면 지친 자리를 다시 건드립니다.",
                        "goal": "(개발용 임시) 관계 이야기를 다시 꺼낼 여지가 있는지 확인",
                        "do": ["(개발용 임시) 만나는 자리에서는 사과나 설득보다 근황과 태도로 보여준다"],
                        "stopCondition": "(개발용 임시) 상대가 관계 이야기를 피하면 그 자리에서 더 밀지 않는다",
                        "avoid": ["(개발용 임시) 만남 전 장문 메시지", "(개발용 임시) 반복적인 연락 시도"]
                      },
                      "chipSeeds": ["(개발용 임시) 만나면 무슨 말부터 해야 할까?", "(개발용 임시) 그날 반응이 애매하면 어떻게 해?"],
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
