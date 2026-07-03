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

// 정밀 판독(2호출) 담당 — v7(진단 우선) 계약.
// 입력(ReadingPacket)은 일부러 얇다: 확률, 등급, 백엔드가 확정한 진단 항목(방향과 순위),
// 관찰 사실, 유저 질문/해석, 관찰 포인트. 요인표, 점프, 관계심리, 유지전망 판정값은 싣지
// 않는다 — 전부 넘기면 2호출이 요인표를 자연어로 복창하는 경향이 실측됐고, 관계심리는
// 2호출이 사실을 보고 직접 고르는 것이 계약이다(reading.yml).
// 판독 지시 전문은 서비스 자산이라 소스에 두지 않고 ReadingProperties(로컬 reading.yml)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingLlm {

    // packet 블록의 머리 문구. MockLlmClient가 이 문구로 판독 호출을 식별한다(개발 스텁 분기).
    public static final String PAYLOAD_HEADER =
            "확정 판정 ReadingPacket(이번 리포트의 유일한 사례 데이터 — 여기 없는 사실을 만들지 마라):";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ReadingProperties readingProperties;

    public CompletableFuture<ReadingDraft> read(Assessment saved, ReunionDiagnosis diagnosis,
                                                String intakeBlock, String level,
                                                List<TypeBandScorer.DiagnosisItem> items) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.getGuide()));
        // packet은 user 턴으로 보낸다 — system만 보내면 전부 systemInstruction으로 빠져
        // contents가 비고, Gemini가 400(contents field is required)으로 거절한다(실측).
        prompt.add(ChatMessage.user(PAYLOAD_HEADER + "\n"
                + packetJson(saved, diagnosis, intakeBlock, level, items)));
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA)
                .thenApply(json -> parse(json, items));
    }

    private String packetJson(Assessment saved, ReunionDiagnosis diagnosis, String intakeBlock,
                              String level, List<TypeBandScorer.DiagnosisItem> items) {
        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        if (facts == null || facts.isEmpty()) {
            // 루브릭이 관찰 사실을 아직 안 내는 동안의 안전망 — 요인 근거를 사실로 승격한다.
            facts = fallbackFacts(saved);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", saved.getProbability());
        out.put("level", level);
        // 누가 이별을 통보했는가. 이게 빠지면 판독이 관찰 사실에서 역할을 역추정하다
        // 두 사람을 뒤집는다(실측: 유저가 통보한 판을 "상대가 이별을 결심한 이유"로 씀).
        // 판정값이 아니라 사실이라 요인표 복창 위험도 없다.
        String declaredBy = declaredBy(saved, diagnosis);
        if (declaredBy != null) {
            out.put("breakupDeclaredBy", declaredBy);
        }
        // 인테이크(나이, 기간, 경과)는 판정값이 아니라 사실이라 복창 위험이 없다 —
        // 루브릭 추출이 INTAKE_ANSWER를 빠뜨려도 기간 없는 리포트가 되지 않게 싣는다.
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            out.put("intake", intakeBlock);
        }

        List<Map<String, Object>> diagnosisRows = new ArrayList<>();
        for (TypeBandScorer.DiagnosisItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", item.key());
            row.put("label", item.label());
            row.put("rank", item.rank());
            row.put("impact", item.impact());
            row.put("factIds", matchFactIds(item, facts));
            if (item.observed() != null && !item.observed().isBlank()) {
                row.put("observed", item.observed());
            }
            row.put("meaning", item.meaning());
            diagnosisRows.add(row);
        }
        out.put("diagnosisItems", diagnosisRows);

        List<Map<String, Object>> factRows = new ArrayList<>();
        int order = 1;
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id());
            row.put("order", order++);
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

        List<Map<String, String>> questions = new ArrayList<>();
        if (diagnosis.directQuestions() != null) {
            int qNo = 1;
            for (String question : diagnosis.directQuestions()) {
                questions.add(Map.of("id", String.format("Q%02d", qNo++), "question", question));
            }
        }
        out.put("directQuestions", questions);

        List<Map<String, String>> interpretations = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            int uNo = 1;
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.format("U%02d", uNo++));
                if (item.factId() != null) {
                    row.put("factId", item.factId());
                }
                row.put("interpretation", item.interpretation());
                interpretations.add(row);
            }
        }
        out.put("userInterpretations", interpretations);

        List<String> watch = new ArrayList<>();
        saved.getWatchPoints().forEach(w -> watch.add(w.getPoint() + " — " + w.getEffect()));
        out.put("watchFor", watch);
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            // 직렬화 실패는 코드 결함 — 판독 없이 진행하게 위로 던진다(판정은 이미 저장됨).
            throw new LlmException();
        }
    }

    // 통보자는 매칭 분류(1호출)가 이미 가려둔 값이다. 없으면 점프 규칙에서 읽는다 —
    // 유저 통보 계열 점프는 이름 자체가 "유저가 통보했다"를 전제로 발동한다.
    private String declaredBy(Assessment saved, ReunionDiagnosis diagnosis) {
        if (diagnosis.matchProfile() != null && diagnosis.matchProfile().dumper() != null
                && !"미상".equals(diagnosis.matchProfile().dumper())) {
            return diagnosis.matchProfile().dumper();
        }
        JumpRule jump = saved.getJumpRule();
        if (jump != null && jump.label().startsWith("유저통보")) {
            return "나";
        }
        return null;
    }

    // 진단 항목의 내부 요약과 겹치는 관찰 사실을 잇는다(최선 노력) — 못 찾으면 빈 목록.
    // 판독이 meaning으로도 풀 수 있어 연결 실패가 치명적이지 않다.
    private List<String> matchFactIds(TypeBandScorer.DiagnosisItem item,
                                      List<ReunionDiagnosis.ReadingFact> facts) {
        // 사유(meaning)가 아니라 관찰(observed)로 잇는다 — 사유는 판정 언어라 사실과 안 겹친다.
        String observed = item.observed();
        if (observed == null || observed.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            if (ids.size() >= 2) {
                break;
            }
            if (fact.fact().contains(observed) || observed.contains(fact.fact())
                    || (fact.quote() != null && observed.contains(fact.quote()))) {
                ids.add(fact.id());
            }
        }
        return ids;
    }

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

    private static Map<String, Object> diagnosisSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("key", Map.of("type", "STRING",
                                "enum", ReadingVocab.DIAGNOSIS_KEYS)),
                        Map.entry("label", Map.of("type", "STRING")),
                        Map.entry("rank", Map.of("type", "INTEGER")),
                        Map.entry("impact", Map.of("type", "STRING",
                                "enum", ReadingVocab.IMPACTS)),
                        Map.entry("verdict", Map.of("type", "STRING")),
                        Map.entry("reading", Map.of("type", "STRING")),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("key", "label", "rank", "impact", "verdict", "reading")),
                Map.entry("propertyOrdering", List.of("key", "label", "rank", "impact", "verdict",
                        "reading", "evidenceIds")));
    }

    private static Map<String, Object> psychologySchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "concept", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING")),
                "required", List.of("concept", "reading"),
                "propertyOrdering", List.of("concept", "reading"));
    }

    private static Map<String, Object> chapterSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("eyebrow", Map.of("type", "STRING")),
                        Map.entry("title", Map.of("type", "STRING")),
                        Map.entry("chapterRole", Map.of("type", "STRING",
                                "enum", ReadingVocab.CHAPTER_ROLES)),
                        Map.entry("interpretationId", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("answer", Map.of("type", "STRING")),
                        Map.entry("reading", Map.of("type", "STRING")),
                        Map.entry("psychology", psychologySchema()),
                        Map.entry("repairPrinciple", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("eyebrow", "title", "chapterRole", "answer",
                        "reading", "evidenceIds")),
                Map.entry("propertyOrdering", List.of("eyebrow", "title", "chapterRole",
                        "interpretationId", "answer", "reading", "psychology", "repairPrinciple",
                        "evidenceIds")));
    }

    private static Map<String, Object> maintenanceSchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "psychology", psychologySchema(),
                        "reading", Map.of("type", "STRING"),
                        "repairPrinciple", Map.of("type", "STRING")),
                "required", List.of("title", "answer", "reading", "repairPrinciple"),
                "propertyOrdering", List.of("title", "answer", "psychology", "reading",
                        "repairPrinciple"));
    }

    private static Map<String, Object> reselectSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "turningPoints", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("title", "answer", "reading", "turningPoints"),
                "propertyOrdering", List.of("title", "answer", "reading", "turningPoints"));
    }

    private static Map<String, Object> finalSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "stateLabel", Map.of("type", "STRING"),
                        "chipSeeds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("stateLabel", "chipSeeds"),
                "propertyOrdering", List.of("stateLabel", "chipSeeds"));
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

    // 판독 응답의 문법. reading.yml(v7)의 출력 형식과 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    // nullable은 정말 없을 수 있는 것만(유지 인사이트, 장 안의 심리/원리) — 탈출구 없는
    // nullable은 조용한 생략 사고가 나서 나머지는 전부 required다.
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("diagnosisSummary", Map.of("type", "STRING")),
                    Map.entry("diagnosis", Map.of("type", "ARRAY", "items", diagnosisSchema())),
                    Map.entry("analysisChapters", Map.of("type", "ARRAY", "items", chapterSchema())),
                    Map.entry("maintenanceInsight", maintenanceSchema()),
                    Map.entry("reselect", reselectSchema()),
                    Map.entry("final", finalSchema()),
                    Map.entry("internal", internalSchema()))),
            Map.entry("required", List.of("diagnosisSummary", "diagnosis", "analysisChapters",
                    "reselect", "final", "internal")),
            Map.entry("propertyOrdering", List.of("diagnosisSummary", "diagnosis",
                    "analysisChapters", "maintenanceInsight", "reselect", "final", "internal")));

    // 개수 상한 — 지시(진단 5~7, 심층 장 2~3, 칩 2~4, 분기점 1~3)의 안전핀.
    private static final int DIAGNOSIS_MAX = 7;
    private static final int CHAPTER_MAX = 4;
    private static final int CHIP_MAX = 4;
    private static final int TURNING_MAX = 3;
    private static final int LIST_TEXT_MAX = 300;

    private ReadingDraft parse(String json, List<TypeBandScorer.DiagnosisItem> items) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));

            // 판독이 쓴 문장(verdict, reading)만 받고 방향과 순위는 백엔드 값으로 덮는다.
            // 모델이 impact를 스스로 정하게 두면 화면의 방향과 확률 계산이 어긋난다 —
            // 같은 리포트 안에서 "높임"인데 확률은 내려간 판이 나온다.
            Map<String, TypeBandScorer.DiagnosisItem> byKey = new LinkedHashMap<>();
            for (TypeBandScorer.DiagnosisItem item : items) {
                byKey.put(item.key(), item);
            }
            Map<String, JsonNode> written = new LinkedHashMap<>();
            for (JsonNode node : root.path("diagnosis")) {
                String key = node.path("key").asText("").trim();
                if (!node.path("verdict").asText("").trim().isBlank()) {
                    written.putIfAbsent(key, node);
                }
            }
            List<ReadingDraft.Diagnosis> diagnosis = new ArrayList<>();
            for (TypeBandScorer.DiagnosisItem item : items) {
                if (diagnosis.size() >= DIAGNOSIS_MAX) {
                    break;
                }
                JsonNode node = written.get(item.key());
                if (node == null) {
                    // 판독이 이 항목의 문장을 안 썼다 — 방향만 있는 줄을 화면에 올리지 않는다.
                    log.warn("판독이 진단 항목을 비움: key={}", item.key());
                    continue;
                }
                diagnosis.add(new ReadingDraft.Diagnosis(item.key(), item.label(),
                        diagnosis.size() + 1, item.impact(),
                        clip(node.path("verdict").asText("").trim(), 500),
                        node.path("reading").asText("").trim(),
                        strings(node.path("evidenceIds"), 10)));
            }
            // 백엔드가 채점하지 않는 항목(현재장벽처럼 확률 기여가 아니라 지금 상태인 것)은
            // 판독이 직접 세울 수 있다 — 그건 막지 않고 방향도 판독 값을 쓴다.
            // 다만 뒤에 붙인다: 측정된 기여도가 없어 채점된 항목과 같은 줄에 세울 수 없다.
            for (Map.Entry<String, JsonNode> entry : written.entrySet()) {
                if (diagnosis.size() >= DIAGNOSIS_MAX || byKey.containsKey(entry.getKey())) {
                    continue;
                }
                String key = entry.getKey();
                if (!ReadingVocab.DIAGNOSIS_LABELS.containsKey(key)) {
                    log.warn("판독 진단 항목 폐기(어휘 밖): key={}", key);
                    continue;
                }
                JsonNode node = entry.getValue();
                String impact = node.path("impact").asText("").trim();
                String label = node.path("label").asText("").trim();
                diagnosis.add(new ReadingDraft.Diagnosis(key,
                        label.isBlank() ? ReadingVocab.DIAGNOSIS_LABELS.get(key) : clip(label, 20),
                        diagnosis.size() + 1,
                        ReadingVocab.IMPACTS.contains(impact) ? impact : "NEUTRAL",
                        clip(node.path("verdict").asText("").trim(), 500),
                        node.path("reading").asText("").trim(),
                        strings(node.path("evidenceIds"), 10)));
            }
            // 진단이 하나도 없으면 v7 리포트가 아니다 — 판독 실패로 처리한다(판정은 유지).
            if (diagnosis.isEmpty()) {
                log.warn("정밀 판독에 진단 항목이 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            List<ReadingDraft.Chapter> chapters = new ArrayList<>();
            for (JsonNode node : root.path("analysisChapters")) {
                if (chapters.size() >= CHAPTER_MAX) {
                    break;
                }
                String title = node.path("title").asText("").trim();
                String answer = node.path("answer").asText("").trim();
                String reading = node.path("reading").asText("").trim();
                if (title.isBlank() || answer.isBlank() || reading.isBlank()) {
                    continue;
                }
                String role = node.path("chapterRole").asText("").trim();
                String principle = node.path("repairPrinciple").asText("").trim();
                String interpretationId = node.path("interpretationId").asText("").trim();
                chapters.add(new ReadingDraft.Chapter(
                        clip(node.path("eyebrow").asText("").trim(), LIST_TEXT_MAX),
                        clip(title, LIST_TEXT_MAX),
                        ReadingVocab.CHAPTER_ROLES.contains(role) ? role : "CORE_CONTRADICTION",
                        interpretationId.isBlank() ? null : clip(interpretationId, 10),
                        clip(answer, 500), reading,
                        psychology(node.path("psychology")),
                        principle.isBlank() ? null : clip(principle, 500),
                        strings(node.path("evidenceIds"), 10)));
            }

            JsonNode reselectNode = root.path("reselect");
            ReadingDraft.Reselect reselect = new ReadingDraft.Reselect(
                    clip(requireText(reselectNode, "title"), LIST_TEXT_MAX),
                    clip(requireText(reselectNode, "answer"), 500),
                    requireText(reselectNode, "reading"),
                    strings(reselectNode.path("turningPoints"), TURNING_MAX));

            JsonNode finalNode = root.path("final");
            ReadingDraft.Fin fin = new ReadingDraft.Fin(
                    clip(requireText(finalNode, "stateLabel"), LIST_TEXT_MAX),
                    strings(finalNode.path("chipSeeds"), CHIP_MAX));

            JsonNode internal = root.path("internal");
            ReadingDraft.Internal states = new ReadingDraft.Internal(
                    state(internal, "nowState", ReadingVocab.NOW_STATES, "MIXED"),
                    state(internal, "resolveState", ReadingVocab.RESOLVE_STATES, "UNSTABLE"),
                    state(internal, "remainState", ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE"),
                    state(internal, "reselectState", ReadingVocab.RESELECT_STATES, "CONDITIONAL"));

            return new ReadingDraft(
                    clip(requireText(root, "diagnosisSummary"), 500),
                    diagnosis, chapters,
                    maintenance(root.path("maintenanceInsight")),
                    reselect, fin, states);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            // 본문에는 사연 기반 서술이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            log.error("정밀 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    private ReadingDraft.Psychology psychology(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String concept = node.path("concept").asText("").trim();
        String reading = node.path("reading").asText("").trim();
        if (concept.isBlank() || reading.isBlank()) {
            return null;
        }
        return new ReadingDraft.Psychology(clip(concept, 100), reading);
    }

    private ReadingDraft.Maintenance maintenance(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String title = node.path("title").asText("").trim();
        String answer = node.path("answer").asText("").trim();
        String principle = node.path("repairPrinciple").asText("").trim();
        if (title.isBlank() || answer.isBlank() || principle.isBlank()) {
            return null;
        }
        return new ReadingDraft.Maintenance(clip(title, LIST_TEXT_MAX), clip(answer, 500),
                psychology(node.path("psychology")),
                node.path("reading").asText("").trim(), clip(principle, 500));
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

    // 요약과 마무리가 비면 리포트로 성립하지 않는다 — 판독만 실패시킨다(판정은 유지).
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
