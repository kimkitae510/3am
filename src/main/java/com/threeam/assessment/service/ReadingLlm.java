package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReadingVocab;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 정밀 판독(2호출) 담당 — 스토리형 리포트(스토리북 v2) 생성.
// 2호출은 원문 사연을 다시 읽지 않는다. 입력은 확정 판정 payload 하나다:
// 판정(확률, 유형, 요인) + 관찰 사실(readingFacts) + 유저 질문/해석 + scoreDrivers(백엔드 산출).
// 확률과 방향은 여기서 만들지도 바꾸지도 않고, 요인 어휘는 유저 지면에 꺼내지 않는다.
// 판독 지시 전문은 서비스 자산이라 소스에 두지 않고 ReadingProperties(로컬 reading.yml)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingLlm {

    // payload 블록의 머리 문구. MockLlmClient가 이 문구로 판독 호출을 식별한다(개발 스텁 분기).
    public static final String PAYLOAD_HEADER =
            "확정 판정 payload(이번 리포트의 유일한 사례 데이터 — 여기 없는 사실을 만들지 마라):";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ReadingProperties readingProperties;

    public CompletableFuture<ReadingDraft> read(Assessment saved, ReunionDiagnosis diagnosis,
                                                String intakeBlock,
                                                List<TypeBandScorer.Driver> drivers,
                                                String displayBand, String coverDriverId) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.getGuide()));
        // payload는 user 턴으로 보낸다 — system만 보내면 전부 systemInstruction으로 빠져
        // contents가 비고, Gemini가 400(contents field is required)으로 거절한다(실측).
        // 의미로도 payload는 지시가 아니라 이번 호출의 입력 데이터라 user 자리가 맞다.
        prompt.add(ChatMessage.user(PAYLOAD_HEADER + "\n"
                + payloadJson(saved, diagnosis, intakeBlock, drivers, displayBand, coverDriverId)));
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA).thenApply(this::parse);
    }

    // 확정 판정 payload. readingFacts가 비면(루브릭 미갱신 등) 요인 근거로 폴백한다 —
    // 사실이 하나도 없으면 리포트가 공중에 뜬다.
    private String payloadJson(Assessment saved, ReunionDiagnosis diagnosis, String intakeBlock,
                               List<TypeBandScorer.Driver> drivers,
                               String displayBand, String coverDriverId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", saved.getProbability());
        out.put("displayBand", displayBand);
        out.put("verdict", saved.getVerdict() != null ? saved.getVerdict().name() : null);
        out.put("breakupType", saved.getBreakupType() != null ? saved.getBreakupType().label() : null);
        out.put("typeEvidence", saved.getTypeEvidence());
        if (saved.getJumpRule() != null && saved.getJumpRule() != JumpRule.NONE) {
            out.put("jumpRule", saved.getJumpRule().label());
        }
        List<Map<String, Object>> factors = new ArrayList<>();
        for (AssessmentFactor factor : saved.getFactors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", factor.getName().label());
            item.put("level", factor.getLevel().label());
            item.put("evidence", factor.getEvidence());
            if (factor.getRationale() != null) {
                item.put("rationale", factor.getRationale());
            }
            factors.add(item);
        }
        out.put("factors", factors);
        if (saved.getRelapseRisk() != null) {
            Map<String, Object> relapse = new LinkedHashMap<>();
            relapse.put("level", saved.getRelapseRisk().label());
            relapse.put("reason", saved.getRelapseReason());
            out.put("relapseRisk", relapse);
        }
        out.put("relationshipPsychology", saved.getRelationshipPsychology());
        List<Map<String, String>> watch = new ArrayList<>();
        saved.getWatchPoints().forEach(w -> watch.add(Map.of(
                "point", w.getPoint(), "effect", w.getEffect())));
        out.put("watchFor", watch);
        out.put("unansweredQuestions", saved.getUnansweredQuestions());
        out.put("reason", saved.getReason());
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            out.put("intake", intakeBlock);
        }

        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        if (facts == null || facts.isEmpty()) {
            facts = fallbackFacts(saved);
        }
        List<Map<String, Object>> factRows = new ArrayList<>();
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id());
            row.put("actor", fact.actor());
            row.put("kind", fact.kind());
            row.put("fact", fact.fact());
            if (fact.quote() != null) {
                row.put("quote", fact.quote());
            }
            if (fact.timing() != null) {
                row.put("timing", fact.timing());
            }
            factRows.add(row);
        }
        out.put("readingFacts", factRows);
        out.put("directQuestions",
                diagnosis.directQuestions() == null ? List.of() : diagnosis.directQuestions());
        List<Map<String, String>> focus = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
                if (item.factId() != null) {
                    row.put("factId", item.factId());
                }
                row.put("interpretation", item.interpretation());
                focus.add(row);
            }
        }
        out.put("userFocus", focus);

        // scoreDrivers — 내부 선별용 delta와 sourceKey는 싣지 않는다(payload 권장안).
        List<Map<String, Object>> driverRows = new ArrayList<>();
        for (TypeBandScorer.Driver driver : drivers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", driver.id());
            row.put("direction", driver.direction());
            row.put("rank", driver.rank());
            row.put("source", driver.source());
            row.put("evidence", driver.evidence());
            row.put("rationale", driver.rationale());
            driverRows.add(row);
        }
        out.put("scoreDrivers", driverRows);
        out.put("coverDriverId", coverDriverId);
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            // 직렬화 실패는 코드 결함 — 판독 없이 진행하게 위로 던진다(판정은 이미 저장됨).
            throw new LlmException();
        }
    }

    // 루브릭이 readingFacts를 아직 안 내는 동안의 안전망 — 요인 근거를 관찰 사실로 승격한다.
    private List<ReunionDiagnosis.ReadingFact> fallbackFacts(Assessment saved) {
        List<ReunionDiagnosis.ReadingFact> out = new ArrayList<>();
        for (AssessmentFactor factor : saved.getFactors()) {
            String evidence = factor.getEvidence();
            if (evidence == null || evidence.isBlank() || ReunionLlm.NO_EVIDENCE.equals(evidence)) {
                continue;
            }
            out.add(new ReunionDiagnosis.ReadingFact(
                    String.format("F%02d", out.size() + 1), "CONTEXT", "ACTION",
                    evidence, null, null));
        }
        return out;
    }

    private static Map<String, Object> mysterySchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        // 상호작용 충돌을 다룬 장에만 붙는 복구 원리 — 독립 심리 페이지 대신
                        // 장 안에서 설명과 원리가 붙어야 검사지 티가 안 난다.
                        "principle", Map.of("type", "STRING", "nullable", true),
                        "evidenceIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "covers", Map.of("type", "ARRAY", "items",
                                Map.of("type", "STRING", "enum", ReadingVocab.MYSTERY_COVERS))),
                "required", List.of("title", "answer", "reading", "evidenceIds", "covers"),
                "propertyOrdering",
                List.of("title", "answer", "reading", "principle", "evidenceIds", "covers"));
    }

    private static Map<String, Object> blockerSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "rank", Map.of("type", "INTEGER"),
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "evidenceIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("rank", "title", "answer", "reading"),
                "propertyOrdering", List.of("rank", "title", "answer", "reading", "evidenceIds"));
    }

    private static Map<String, Object> reselectSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "open", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "conditions", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "watchFor", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("title", "answer", "open", "conditions"),
                "propertyOrdering", List.of("title", "answer", "open", "conditions", "watchFor"));
    }

    private static Map<String, Object> phaseSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "label", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "chipSeeds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("label", "reading", "chipSeeds"),
                "propertyOrdering", List.of("label", "reading", "chipSeeds"));
    }

    private static Map<String, Object> followUpSchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "question", Map.of("type", "STRING"),
                        "whyItMatters", Map.of("type", "STRING")),
                "required", List.of("question", "whyItMatters"),
                "propertyOrdering", List.of("question", "whyItMatters"));
    }

    private static Map<String, Object> internalSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "nowState", Map.of("type", "STRING", "enum", ReadingVocab.NOW_STATES),
                        "resolveState", Map.of("type", "STRING", "enum", ReadingVocab.RESOLVE_STATES),
                        "remainState", Map.of("type", "STRING", "enum", ReadingVocab.REMAIN_STATES),
                        "reselectState", Map.of("type", "STRING", "enum", ReadingVocab.RESELECT_STATES)),
                "required", List.of("nowState", "resolveState", "remainState", "reselectState"),
                "propertyOrdering", List.of("nowState", "resolveState", "remainState", "reselectState"));
    }

    // 판독 응답의 문법. reading.yml(스토리북 v2)의 출력 필드와 짝 — 지시를 고쳐 필드가
    // 바뀌면 여기도 같이. relationshipRepair와 followUp만 nullable(정말 없을 수 있는 값 —
    // 탈출구 없는 nullable은 조용한 생략 사고가 나서 나머지는 전부 required).
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("coverVerdict", Map.of("type", "STRING")),
                    Map.entry("coverReason", Map.of("type", "STRING")),
                    Map.entry("mysteries", Map.of("type", "ARRAY", "items", mysterySchema())),
                    Map.entry("blockers", Map.of("type", "ARRAY", "items", blockerSchema())),
                    Map.entry("reselect", reselectSchema()),
                    Map.entry("phase", phaseSchema()),
                    Map.entry("followUp", followUpSchema()),
                    Map.entry("internal", internalSchema()))),
            Map.entry("required", List.of("coverVerdict", "coverReason", "mysteries",
                    "blockers", "reselect", "phase", "internal")),
            Map.entry("propertyOrdering", List.of("coverVerdict", "coverReason", "mysteries",
                    "blockers", "reselect", "phase", "followUp", "internal")));

    // 개수 상한 — 스토리북 지시(미스터리 3~5, 장애물 1~2, 칩 4)의 안전핀.
    // 장애물 2 상한은 설계값이다: 3순위 보고서는 다시 검사지가 된다 — 가장 큰 것 하나에
    // 무게가 실리고 나머지는 부속이어야 한다.
    private static final int MYSTERY_MAX = 5;
    private static final int BLOCKER_MAX = 2;
    private static final int CHIP_MAX = 4;
    private static final int LIST_TEXT_MAX = 300;

    private ReadingDraft parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));

            List<ReadingDraft.Mystery> mysteries = new ArrayList<>();
            for (JsonNode node : root.path("mysteries")) {
                if (mysteries.size() >= MYSTERY_MAX) {
                    break;
                }
                String title = node.path("title").asText("").trim();
                String answer = node.path("answer").asText("").trim();
                String reading = node.path("reading").asText("").trim();
                if (title.isBlank() || answer.isBlank() || reading.isBlank()) {
                    continue;
                }
                String principle = node.path("principle").asText("").trim();
                mysteries.add(new ReadingDraft.Mystery(clip(title, LIST_TEXT_MAX),
                        clip(answer, 500), reading,
                        principle.isBlank() ? null : clip(principle, 500),
                        strings(node.path("evidenceIds"), 10),
                        strings(node.path("covers"), 6)));
            }
            // 미스터리가 하나도 없으면 스토리 리포트가 아니다 — 판독 실패로 처리한다(판정은 유지).
            if (mysteries.isEmpty()) {
                log.warn("정밀 판독에 미스터리 장이 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            List<ReadingDraft.Blocker> blockers = new ArrayList<>();
            for (JsonNode node : root.path("blockers")) {
                if (blockers.size() >= BLOCKER_MAX) {
                    break;
                }
                String title = node.path("title").asText("").trim();
                String answer = node.path("answer").asText("").trim();
                if (title.isBlank() || answer.isBlank()) {
                    continue;
                }
                // rank는 모델 값 대신 배열 순서로 다시 매긴다 — 1,1,3 같은 값이 화면에 그대로 뜬다.
                blockers.add(new ReadingDraft.Blocker(blockers.size() + 1,
                        clip(title, LIST_TEXT_MAX), clip(answer, 500),
                        node.path("reading").asText("").trim(),
                        strings(node.path("evidenceIds"), 10)));
            }

            JsonNode reselectNode = root.path("reselect");
            ReadingDraft.Reselect reselect = new ReadingDraft.Reselect(
                    clip(requireText(reselectNode, "title"), LIST_TEXT_MAX),
                    clip(requireText(reselectNode, "answer"), 500),
                    strings(reselectNode.path("open"), 2),
                    strings(reselectNode.path("conditions"), 3),
                    strings(reselectNode.path("watchFor"), 2));

            JsonNode phaseNode = root.path("phase");
            ReadingDraft.Phase phase = new ReadingDraft.Phase(
                    clip(requireText(phaseNode, "label"), LIST_TEXT_MAX),
                    clip(requireText(phaseNode, "reading"), 500),
                    strings(phaseNode.path("chipSeeds"), CHIP_MAX));

            ReadingDraft.FollowUp followUp = null;
            JsonNode followNode = root.path("followUp");
            if (followNode.isObject()) {
                String question = followNode.path("question").asText("").trim();
                String why = followNode.path("whyItMatters").asText("").trim();
                if (!question.isBlank() && !why.isBlank()) {
                    followUp = new ReadingDraft.FollowUp(clip(question, LIST_TEXT_MAX),
                            clip(why, LIST_TEXT_MAX));
                }
            }

            JsonNode internal = root.path("internal");
            ReadingDraft.Internal states = new ReadingDraft.Internal(
                    state(internal, "nowState", ReadingVocab.NOW_STATES, "MIXED"),
                    state(internal, "resolveState", ReadingVocab.RESOLVE_STATES, "UNSTABLE"),
                    state(internal, "remainState", ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE"),
                    state(internal, "reselectState", ReadingVocab.RESELECT_STATES, "CONDITIONAL"));

            return new ReadingDraft(
                    clip(requireText(root, "coverVerdict"), LIST_TEXT_MAX),
                    clip(requireText(root, "coverReason"), 500),
                    mysteries, blockers, reselect, phase, followUp, states);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            // 본문에는 사연 기반 서술이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            log.error("정밀 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    private List<String> strings(JsonNode array, int max) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.asText("").trim();
            if (value.isBlank() || out.size() >= max) {
                continue;
            }
            out.add(clip(value, LIST_TEXT_MAX));
        }
        return out;
    }

    // 스키마가 enum을 강제하지만 salvage 경로(잘린 응답 복구)는 뚫릴 수 있어 한 번 더 거른다.
    private String state(JsonNode node, String field, List<String> allowed, String fallback) {
        String value = node.path(field).asText("").trim();
        if (allowed.contains(value)) {
            return value;
        }
        log.warn("판독 state 폐기(사전에 없음): {}={} — {}로 대체", field, value, fallback);
        return fallback;
    }

    // 표지와 마무리가 비면 리포트로 성립하지 않는다 — 판정은 이미 저장됐으니 판독만 실패시킨다.
    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            log.warn("정밀 판독 필수 필드 누락: {}", field);
            throw new LlmException();
        }
        return value;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
