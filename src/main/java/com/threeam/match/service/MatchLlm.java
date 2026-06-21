package com.threeam.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import com.threeam.match.MatchBand;
import com.threeam.match.MatchProperties;
import com.threeam.match.entity.MatchPick;
import com.threeam.match.entity.ReunionCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 후보 사례를 읽고 몇 건을 고른다. 서버 점수는 태그 겹침만 보므로 본문이 실제로 닮았는지는
// 모른다 — 이 호출의 값어치는 거기에 있다. 그래서 후보의 본문을 통째로 넘긴다.
//
// 선택 지시문은 서비스 자산이라 코드에 두지 않는다(rubric.yml, gitignore).
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchLlm {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MatchProperties matchProperties;

    public CompletableFuture<MatchPick.Picks> select(String userDigest, List<ChatMessage> conversation,
                                                     CaseCandidates.Pool pool, MatchBand band) {
        List<ChatMessage> prompt = new ArrayList<>();
        String guide = matchProperties.getSelectGuide();
        if (guide != null && !guide.isBlank()) {
            prompt.add(ChatMessage.system(guide));
        }
        // 뽑을 수 있는 상한. 최소 장수는 안 준다 — 못 채우면 덜 주는 게 억지로 채우는 것보다 낫다.
        prompt.add(ChatMessage.system(
                "이번 판에서 고를 수 있는 상한: 재회한 사례 %d건까지, 재회 못 한 사례 %d건까지."
                        .formatted(band.successMax(), band.failMax())));
        prompt.add(ChatMessage.system("후보 사례:\n" + render(pool)));
        if (userDigest != null && !userDigest.isBlank()) {
            prompt.add(ChatMessage.system("이 유저의 상황 요지:\n" + userDigest));
        }
        prompt.addAll(conversation);
        return llmClient.generateJsonDeep(prompt, schema(pool)).thenApply(this::parse);
    }

    // 사례 카드에 안 나가는 필드(tone, length, sourceType 등)는 넘기지 않는다 — 판단에
    // 쓸 값이 아니고, 후보 열넷이면 그 낭비가 매 호출 쌓인다.
    private String render(CaseCandidates.Pool pool) {
        StringBuilder sb = new StringBuilder();
        for (ReunionCase target : pool.all()) {
            sb.append("[id=").append(target.getId()).append("] ")
                    .append(target.getOutcome()).append(" / ")
                    .append(target.getReason()).append(" / ")
                    .append(String.join(",", target.subReasonList())).append(" / 통보=")
                    .append(target.getDumper()).append(" / 잘못=")
                    .append(target.getFault()).append(" / ")
                    .append(target.getContactState()).append('\n')
                    .append(target.getStory()).append("\n\n");
        }
        return sb.toString();
    }

    // caseId를 후보 id의 enum으로 못 박는 게 핵심이다 — 없는 사례를 가리키는 응답이
    // 생성 단계에서 아예 나올 수 없다. 후보가 매번 달라지므로 스키마도 매번 만든다.
    private Map<String, Object> schema(CaseCandidates.Pool pool) {
        List<String> ids = pool.all().stream().map(target -> String.valueOf(target.getId())).toList();
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "summary", Map.of("type", "STRING"),
                        "picks", Map.of(
                                "type", "ARRAY",
                                "items", Map.of(
                                        "type", "OBJECT",
                                        "properties", Map.of(
                                                "caseId", Map.of("type", "STRING", "enum", ids),
                                                // 태그는 자유 문구다. 사전으로 못 박으면 규칙 기반과
                                                // 다를 게 없어져 본문을 읽는 값어치가 사라진다.
                                                "tags", Map.of("type", "ARRAY",
                                                        "items", Map.of("type", "STRING")),
                                                "similarity", Map.of("type", "STRING"),
                                                "reading", Map.of("type", "STRING")),
                                        "required", List.of("caseId", "tags", "similarity", "reading"),
                                        "propertyOrdering",
                                        List.of("caseId", "tags", "similarity", "reading")))),
                // picks가 먼저다 — 고른 뒤에 견주게 해야 요약이 선택을 끌고 가지 않는다.
                "required", List.of("picks", "summary"),
                "propertyOrdering", List.of("picks", "summary"));
    }

    private MatchPick.Picks parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(raw));
            List<MatchPick> picks = new ArrayList<>();
            for (JsonNode node : root.path("picks")) {
                String id = node.path("caseId").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                List<String> tags = new ArrayList<>();
                for (JsonNode tag : node.path("tags")) {
                    String text = tag.asText("").trim();
                    if (!text.isEmpty()) {
                        tags.add(text);
                    }
                }
                picks.add(new MatchPick(Long.parseLong(id.trim()), tags,
                        node.path("similarity").asText(""),
                        node.path("reading").asText("")));
            }
            return new MatchPick.Picks(root.path("summary").asText(null), picks);
        } catch (Exception e) {
            log.error("매칭 선택 응답 파싱 실패 raw={}", raw, e);
            throw new LlmException();
        }
    }
}
