package com.threeam.chip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ProbabilityBand;
import com.threeam.chip.dto.ChipView;
import com.threeam.llm.LlmJson;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

// 추천 질문 칩의 유일한 저장소. 정의도 프롬프트도 DB에 넣지 않는다 — 40개 읽기 전용이고
// 유저별 차이가 없어서 DB가 주는 게 없고, yml이 원본인데 복사본을 두면 반드시 어긋난다.
// CaseStore와 같은 방식: 서비스 자산 파일(gitignore)을 시동 때 한 번 읽어 메모리에 상주시킨다.
//
// application.yml의 spring.config.import로 안 읽는 이유: 모듈과 마이크로 프롬프트의 키가
// ACTION_NOW 같은 대문자에 밑줄이라 스프링 완화 바인딩이 소문자로 뭉갠다. 자산 파일 전체를
// "[ACTION_NOW]" 꼴로 감싸는 대신 여기서 직접 파싱한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChipStore {

    // 진단 결과가 있어야 답이 성립하는 모듈. 없으면 후보 목록에서 아예 뺀다 —
    // 보이면 고르고, 고르면 유저가 누르고, 누르면 읽을 데이터 없이 지어낸다.
    private static final String NEEDS_ASSESSMENT = "DIAGNOSIS_EXPLAIN";

    private final ObjectMapper objectMapper;

    // 자산 파일은 빌드 산출물(resources)에 넣지 않는다 — 배포물에 포함돼 새는 경로가 된다.
    @Value("${chip.menu-file:chip-menu.yml}")
    private String menuFile;

    @Value("${chip.modules-file:chip-modules.yml}")
    private String modulesFile;

    private String inlinePrompt = "";
    private String matchPrompt = "";
    private String commonPrompt = "";
    private List<ChipDefinition> catalog = List.of();
    private Map<String, ChipDefinition> byId = Map.of();
    private Map<String, ChipInputPreset> presets = Map.of();
    private Map<String, String> modules = Map.of();
    private Map<String, String> microPrompts = Map.of();
    private Map<String, DiagnosisContext> diagnosisPolicies = Map.of();

    @PostConstruct
    void load() {
        Map<String, Object> menu = readYaml(menuFile, "칩 메뉴");
        Map<String, Object> mods = readYaml(modulesFile, "칩 모듈");

        inlinePrompt = text(menu.get("chip-inline"));
        matchPrompt = text(menu.get("chip-match"));
        commonPrompt = text(mods.get("chip-common"));
        presets = readPresets(menu.get("chip-input-presets"));
        diagnosisPolicies = readDiagnosisPolicies(menu.get("chip-module-diagnosis"));
        modules = readTextMap(mods.get("chip-modules"));
        microPrompts = readTextMap(mods.get("chip-micro-prompts"));
        catalog = readCatalog(menu.get("chip-catalog"));

        Map<String, ChipDefinition> index = new LinkedHashMap<>();
        for (ChipDefinition chip : catalog) {
            index.put(chip.id(), chip);
        }
        byId = Map.copyOf(index);

        verify();
        log.info("칩 {}개, 모듈 {}개, 마이크로 프롬프트 {}개 로드",
                catalog.size(), modules.size(), microPrompts.size());
    }

    // 자산이 비거나 어긋나도 기동은 막지 않는다(칩만 안 뜨고 상담은 돈다). 다만 조용히 품질만
    // 떨어지는 게 제일 찾기 어려운 고장이라 시동 때 한 번 크게 남긴다 — PromptFilesCheck와 같은 취지.
    private void verify() {
        if (catalog.isEmpty()) {
            log.error("칩 카탈로그가 비었다: {} — 추천 질문이 영영 안 뜬다", Path.of(menuFile).toAbsolutePath());
            return;
        }
        if (inlinePrompt.isBlank()) {
            log.error("칩 선정 지시(chip-inline)가 비었다 — 상담자가 기준 없이 고른다");
        }
        for (ChipDefinition chip : catalog) {
            if (!modules.containsKey(chip.module())) {
                log.error("칩 {}의 모듈 {}가 chip-modules에 없다 — 누르면 전문 지시 없이 답한다",
                        chip.id(), chip.module());
            }
            if (!microPrompts.containsKey(chip.id())) {
                log.warn("칩 {}의 마이크로 프롬프트가 없다 — 모듈 지시만으로 답한다", chip.id());
            }
            if (chip.interaction() == ChipInteraction.INPUT && preset(chip) == null) {
                log.error("INPUT 칩 {}의 입력 프리셋({})이 없다 — 화면이 입력창을 못 띄운다",
                        chip.id(), chip.inputPreset());
            }
        }
    }

    private Map<String, Object> readYaml(String file, String label) {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.error("{} 파일 없음: {} — 추천 질문 기능이 통째로 꺼진다", label, path.toAbsolutePath());
            return Map.of();
        }
        try {
            Object root = new Yaml().load(Files.readString(path));
            return root instanceof Map<?, ?> map ? cast(map) : Map.of();
        } catch (Exception e) {
            log.error("{} 파일 파싱 실패: {}", label, path.toAbsolutePath(), e);
            return Map.of();
        }
    }

    private List<ChipDefinition> readCatalog(Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<ChipDefinition> loaded = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> chip = cast(map);
            String id = text(chip.get("id"));
            if (id.isBlank()) {
                continue;
            }
            loaded.add(new ChipDefinition(
                    id,
                    text(chip.get("label")),
                    text(chip.get("module")),
                    text(chip.get("selectorDescription")),
                    text(chip.get("requiresBand")),
                    interaction(chip.get("interactionType"), id),
                    emptyToNull(text(chip.get("inputPreset")))));
        }
        return List.copyOf(loaded);
    }

    // 모르는 값은 DIRECT로 떨어뜨린다 — 오타 하나로 칩이 사라지는 것보다 바로 전송되는 쪽이 덜 나쁘다.
    private ChipInteraction interaction(Object raw, String chipId) {
        String value = text(raw);
        if (value.isBlank()) {
            return ChipInteraction.DIRECT;
        }
        try {
            return ChipInteraction.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("칩 {}의 interactionType이 사전 밖 값이다: {} — DIRECT로 다룬다", chipId, value);
            return ChipInteraction.DIRECT;
        }
    }

    private Map<String, ChipInputPreset> readPresets(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, ChipInputPreset> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : cast(map).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> body) {
                Map<String, Object> preset = cast(body);
                loaded.put(entry.getKey(), new ChipInputPreset(
                        text(preset.get("title")),
                        text(preset.get("placeholder")),
                        text(preset.get("helper")),
                        text(preset.get("submitLabel"))));
            }
        }
        return Map.copyOf(loaded);
    }

    // 표에 없는 모듈은 NONE이다 — 새 모듈을 만들 때 진단을 실을지 안 실을지 안 정했으면
    // 안 싣는 쪽이 맞다. 모르고 실어서 확률에 끌리는 것보다 모르고 빠지는 편이 낫다.
    private Map<String, DiagnosisContext> readDiagnosisPolicies(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, DiagnosisContext> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : cast(map).entrySet()) {
            String value = text(entry.getValue());
            try {
                loaded.put(entry.getKey(), DiagnosisContext.valueOf(value.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("모듈 {}의 진단 주입 정책이 사전 밖 값이다: {} — NONE으로 다룬다",
                        entry.getKey(), value);
            }
        }
        return Map.copyOf(loaded);
    }

    private Map<String, String> readTextMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : cast(map).entrySet()) {
            String value = text(entry.getValue());
            if (!value.isBlank()) {
                loaded.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(loaded);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }

    public List<ChipDefinition> all() {
        return catalog;
    }

    public ChipDefinition find(String chipId) {
        return chipId == null ? null : byId.get(chipId);
    }

    // 상담 답변 프롬프트 끝에 붙는 칩 선정 지시. 40개 목록은 코드가 이어 붙인다.
    public String inlinePrompt() {
        return inlinePrompt;
    }

    // 자유입력이 어느 갈래인지 가리는 저가 호출용 지시.
    public String matchPrompt() {
        return matchPrompt;
    }

    // 판별 결과. 카탈로그에 없거나 지금 제시할 수 없는 칩이면 null이다 —
    // 억지로 붙이면 안 맞는 전문 지시가 실려 딴 답이 나간다.
    public ChipDefinition matched(String json, Integer probability) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            String id = root.path("id").asText(null);
            if (id == null || id.isBlank() || "null".equals(id)) {
                return null;
            }
            ChipDefinition chip = byId.get(id.strip());
            return (chip != null && offerable(chip, probability)) ? chip : null;
        } catch (Exception e) {
            log.warn("자유입력 판별 응답 파싱 실패: {}", json, e);
            return null;
        }
    }

    // 상담자에게 보여줄 후보 목록. 진단이 없으면 답할 데이터가 없는 칩을 아예 안 보여준다 —
    // 보이면 고르고, 고르면 유저가 누르고, 누르면 지어낸다.
    public String catalogBlock(Integer probability) {
        StringBuilder block = new StringBuilder("[chip catalog] (형식: id | label | 언제 고르는가)\n");
        for (ChipDefinition chip : catalog) {
            if (offerable(chip, probability)) {
                block.append(chip.selectorLine()).append('\n');
            }
        }
        return block.toString();
    }

    // 진단이 없으면 진단 설명 칩을 통째로 빼고, 있으면 대역이 맞는 것만 남긴다.
    // 상담 프롬프트에는 진단 데이터가 안 실리므로 상담자는 확률을 모른다 — "낮게 나왔나요"와
    // "높게 나왔나요" 중 무엇이 맞는지 판단할 재료가 없어서, 코드가 대신 걸러 안 맞는 칩은
    // 목록에 보이지도 않게 한다. 확률을 프롬프트에 실어 풀면 진단을 안 실어야 하는 턴까지 숫자가 들어간다.
    private boolean offerable(ChipDefinition chip, Integer probability) {
        if (!NEEDS_ASSESSMENT.equals(chip.module())) {
            return true;
        }
        if (probability == null) {
            return false;
        }
        return switch (chip.requiresBand()) {
            case "LOW" -> ProbabilityBand.isLow(probability);
            case "HIGH" -> ProbabilityBand.isHigh(probability);
            default -> true;
        };
    }

    public String commonPrompt() {
        return commonPrompt;
    }

    public String modulePrompt(ChipDefinition chip) {
        return modules.get(chip.module());
    }

    public String microPrompt(ChipDefinition chip) {
        return microPrompts.get(chip.id());
    }

    // 이 칩의 상담에 진단 데이터를 얼마나 실을지. 칩이 아니라 모듈 단위로 정한다 —
    // 같은 모듈의 칩들은 어차피 같은 종류의 재료를 읽는다.
    public DiagnosisContext diagnosisContext(ChipDefinition chip) {
        return diagnosisPolicies.getOrDefault(chip.module(), DiagnosisContext.NONE);
    }

    public ChipInputPreset preset(ChipDefinition chip) {
        return chip.inputPreset() == null ? null : presets.get(chip.inputPreset());
    }

    // 저장된 id 목록을 화면용으로 편다. 모르는 id는 조용히 빠진다 — 칩을 지운 뒤에도
    // 지난 대화의 답변 행에는 그 id가 남아 있다.
    // 저장 형식은 [{"id":..,"label":..}, ..] JSON이다. 라벨까지 남겨야 새로고침 뒤에도
    // 셀렉터가 다시 쓴 문장이 그대로 뜬다.
    public String encode(List<SuggestedChip> suggested) {
        if (suggested == null || suggested.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(suggested);
        } catch (Exception e) {
            log.warn("추천 칩 직렬화 실패 — 빈 값으로 닫는다", e);
            return "";
        }
    }

    // 라벨 도입 전에 저장된 행은 "A,B,C" 꼴이다. 기록은 고치지 않으므로 여기서 둘 다 읽는다.
    public List<SuggestedChip> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        String text = encoded.strip();
        if (!text.startsWith("[")) {
            return Arrays.stream(text.split(",")).map(String::strip)
                    .filter(id -> !id.isEmpty())
                    .map(id -> new SuggestedChip(id, null))
                    .toList();
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            List<SuggestedChip> loaded = new ArrayList<>();
            for (JsonNode node : root) {
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    loaded.add(new SuggestedChip(id.strip(), node.path("label").asText(null)));
                }
            }
            return List.copyOf(loaded);
        } catch (Exception e) {
            log.warn("추천 칩 역직렬화 실패 — 칩 없이 지나간다: {}", text, e);
            return List.of();
        }
    }

    // 상담 답변 꼬리의 JSON을 저장 형식으로 옮긴다. 카탈로그에 없는 id, 중복, 못 쓸 라벨을
    // 여기서 걸러낸다 — 라벨은 누르면 그대로 유저 말풍선이 되므로 길이와 빈 값은 코드가 막는다.
    // 없는 사실을 지어냈는지는 코드로 못 본다(chip-inline 규칙이 맡는다).
    public List<SuggestedChip> parseTail(String json, Integer probability,
                                         boolean rewriteLabel, int maxLabel, int limit) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<SuggestedChip> picked = new ArrayList<>();
            for (JsonNode node : root) {
                String raw = node.isTextual() ? node.asText(null) : node.path("id").asText(null);
                if (raw == null) {
                    continue;
                }
                String id = raw.strip();
                ChipDefinition chip = byId.get(id);
                if (chip == null || picked.stream().anyMatch(c -> c.id().equals(id))) {
                    continue;
                }
                // 목록에서 뺐는데도 써낸 경우. 후보에 없던 걸 고른 것이라 여기서도 막는다 —
                // 통과시키면 읽을 진단 데이터 없이, 혹은 안 맞는 대역으로 결과를 설명하게 된다.
                if (!offerable(chip, probability)) {
                    log.warn("후보에 없던 칩을 골랐다: {} — 버린다(확률={})", id, probability);
                    continue;
                }
                picked.add(new SuggestedChip(id, usableLabel(node, id, rewriteLabel, maxLabel)));
                if (picked.size() == limit) {
                    break;
                }
            }
            return List.copyOf(picked);
        } catch (Exception e) {
            log.warn("답변 꼬리의 추천 칩 파싱 실패 — 칩 없이 지나간다: {}", json, e);
            return List.of();
        }
    }

    private String usableLabel(JsonNode node, String chipId, boolean rewriteLabel, int maxLabel) {
        if (!rewriteLabel) {
            return null;
        }
        String label = node.path("label").asText("").strip();
        if (label.isEmpty()) {
            return null;
        }
        if (label.length() > maxLabel) {
            log.warn("재작성 라벨이 너무 길어 원문으로 되돌림 chipId={} {}자: {}",
                    chipId, label.length(), label);
            return null;
        }
        return label;
    }

    public List<String> decodeIds(String encoded) {
        return decode(encoded).stream().map(SuggestedChip::id).toList();
    }

    public List<ChipView> views(String encoded) {
        return views(decode(encoded));
    }

    public List<ChipView> views(List<SuggestedChip> suggested) {
        List<ChipView> views = new ArrayList<>();
        for (SuggestedChip item : suggested) {
            ChipDefinition chip = byId.get(item.id());
            if (chip != null) {
                // 저장된 문장이 있으면 그것을 쓴다. 셀렉터가 유저 상황에 맞게 다시 쓴 말이고,
                // 없거나 비었으면 카탈로그 원문으로 돌아간다.
                views.add(ChipView.of(chip, preset(chip), item.label()));
            }
        }
        return List.copyOf(views);
    }

    // 전체 목록 화면("다른 질문 보기")용. 카탈로그 순서가 곧 모듈별 묶음 순서다.
    // 추천 3개와 같은 기준으로 거른다 — 여기만 안 거르면 진단을 안 받은 유저가 목록에서
    // 진단 설명 칩을 골라 읽을 데이터 없이 결과를 설명하게 만든다.
    public List<ChipView> allViews(Integer probability) {
        return catalog.stream()
                .filter(chip -> offerable(chip, probability))
                .map(chip -> ChipView.of(chip, preset(chip)))
                .toList();
    }
}
