package com.threeam.chip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

// 자산 파일이 gitignore라 CI에도 개발 머신에도 없을 수 있다. 실제 파일이 아니라
// 같은 문법의 임시 파일로 파싱 규칙만 고정한다.
class ChipStoreTest {

    @TempDir
    Path dir;

    private ChipStore chipStore;

    private static final String MENU = """
            chip-inline: |
              답변을 마친 뒤 세 개를 고른다.

            chip-catalog:

              - id: CONTACT_NOW
                label: "지금 연락해도 될까요?"
                module: CONTACT
                interactionType: DIRECT
                selectorDescription: "선연락이 적절한지 판단해야 할 때."

              - id: UPDATE_EVENT
                label: "상대에게 새로운 일이 있었어요"
                module: UPDATE
                interactionType: INPUT
                inputPreset: EVENT_UPDATE
                selectorDescription: "새로운 사건을 알려주려 할 때."

              - id: DIAG_LOW
                label: "왜 생각보다 낮게 나왔나요?"
                module: DIAGNOSIS_EXPLAIN
                interactionType: 오타
                requiresBand: LOW
                selectorDescription: "낮은 결과를 받았을 때."

            chip-module-diagnosis:
              CONTACT: NONE
              DIAGNOSIS_EXPLAIN: FULL
              UPDATE: 오타

            chip-input-presets:

              EVENT_UPDATE:
                title: "어떤 일이 있었나요?"
                placeholder: "새로 생긴 일을 적어주세요."
                helper: "누가 먼저 행동했는지도 적어주면 좋습니다."
                submitLabel: "시현에게 보내기"
            """;

    private static final String MODULES = """
            chip-common: |
              고른 질문이 이번 턴의 주제다.

            chip-modules:

              CONTACT: |
                [연락 판단 상담]

              UPDATE: |
                [새로운 사건 업데이트]

              DIAGNOSIS_EXPLAIN: |
                [진단 결과 설명]

            chip-micro-prompts:

              CONTACT_NOW: |
                결론부터 분명하게 말한다.
            """;

    @BeforeEach
    void setUp() throws Exception {
        Path menu = dir.resolve("menu.yml");
        Path modules = dir.resolve("modules.yml");
        Files.writeString(menu, MENU);
        Files.writeString(modules, MODULES);

        chipStore = new ChipStore(new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(chipStore, "menuFile", menu.toString());
        ReflectionTestUtils.setField(chipStore, "modulesFile", modules.toString());
        ReflectionTestUtils.invokeMethod(chipStore, "load");
    }

    @Test
    @DisplayName("칩 정의와 모듈, 마이크로 프롬프트를 id로 찾을 수 있다")
    void loadsCatalogAndPrompts() {
        ChipDefinition chip = chipStore.find("CONTACT_NOW");

        assertThat(chip.label()).isEqualTo("지금 연락해도 될까요?");
        assertThat(chip.interaction()).isEqualTo(ChipInteraction.DIRECT);
        assertThat(chipStore.modulePrompt(chip)).contains("[연락 판단 상담]");
        assertThat(chipStore.microPrompt(chip)).contains("결론부터");
        assertThat(chipStore.commonPrompt()).contains("이번 턴의 주제");
    }

    @Test
    @DisplayName("INPUT 칩은 입력 프리셋까지 함께 나간다")
    void resolvesInputPreset() {
        ChipDefinition chip = chipStore.find("UPDATE_EVENT");

        assertThat(chip.interaction()).isEqualTo(ChipInteraction.INPUT);
        assertThat(chipStore.preset(chip).title()).isEqualTo("어떤 일이 있었나요?");
        assertThat(chipStore.preset(chip).submitLabel()).isEqualTo("시현에게 보내기");
    }

    // 오타 하나로 칩이 통째로 사라지는 것보다 바로 전송되는 쪽이 덜 나쁘다.
    @Test
    @DisplayName("사전 밖 interactionType은 DIRECT로 떨어진다")
    void unknownInteractionFallsBackToDirect() {
        assertThat(chipStore.find("DIAG_LOW").interaction()).isEqualTo(ChipInteraction.DIRECT);
    }

    @Test
    @DisplayName("후보 목록 줄은 id, label, 언제 고르는가 셋뿐이다")
    void selectorLineShape() {
        assertThat(chipStore.find("DIAG_LOW").selectorLine())
                .isEqualTo("- DIAG_LOW | 왜 생각보다 낮게 나왔나요? | 낮은 결과를 받았을 때.");
    }

    // 모듈과 마이크로 프롬프트는 서비스 자산이라 화면으로 나가면 안 된다.
    @Test
    @DisplayName("화면용 칩에는 프롬프트가 실리지 않는다")
    void viewsCarryNoPrompts() {
        String encoded = chipStore.encode(java.util.List.of(
                new SuggestedChip("CONTACT_NOW", null), new SuggestedChip("없는칩", null)));

        assertThat(chipStore.views(encoded))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.id()).isEqualTo("CONTACT_NOW");
                    assertThat(view.label()).isEqualTo("지금 연락해도 될까요?");
                    assertThat(view.inputPreset()).isNull();
                });
    }

    // 셀렉터가 다시 쓴 문장이 있으면 그것을 쓰고, 없으면 카탈로그 원문으로 돌아간다.
    @Test
    @DisplayName("재작성된 라벨이 저장돼 있으면 그 문장으로 그린다")
    void viewsUseStoredLabel() {
        String encoded = chipStore.encode(java.util.List.of(
                new SuggestedChip("CONTACT_NOW", "토요일 전에 먼저 연락해도 될까요?")));

        assertThat(chipStore.views(encoded)).singleElement()
                .satisfies(view -> assertThat(view.label()).isEqualTo("토요일 전에 먼저 연락해도 될까요?"));
    }

    // 라벨 도입 전에 저장된 행은 "A,B" 꼴이다. 기록은 고치지 않으므로 둘 다 읽어야 한다.
    @Test
    @DisplayName("쉼표로 저장된 옛 형식도 읽는다")
    void readsLegacyCommaFormat() {
        assertThat(chipStore.views("CONTACT_NOW,UPDATE_EVENT")).hasSize(2);
        assertThat(chipStore.decodeIds("CONTACT_NOW,UPDATE_EVENT"))
                .containsExactly("CONTACT_NOW", "UPDATE_EVENT");
        assertThat(chipStore.views("")).isEmpty();
        assertThat(chipStore.views((String) null)).isEmpty();
    }

    // 표에 없거나 값이 깨진 모듈은 NONE이다 — 모르고 실어서 확률에 끌리는 것보다
    // 모르고 빠지는 편이 낫다.
    @Test
    @DisplayName("진단 주입 정책은 모듈 단위로 읽고, 못 읽으면 NONE이다")
    void readsDiagnosisPolicyPerModule() {
        assertThat(chipStore.diagnosisContext(chipStore.find("CONTACT_NOW")))
                .isEqualTo(DiagnosisContext.NONE);
        assertThat(chipStore.diagnosisContext(chipStore.find("DIAG_LOW")))
                .isEqualTo(DiagnosisContext.FULL);
        // 사전 밖 값(UPDATE)과 표에 아예 없는 모듈 둘 다 NONE으로 떨어진다
        assertThat(chipStore.diagnosisContext(chipStore.find("UPDATE_EVENT")))
                .isEqualTo(DiagnosisContext.NONE);
    }

    // 진단이 없으면 진단 설명 칩은 답할 데이터가 없다. 보이면 고르고, 고르면 유저가 누르고,
    // 누르면 지어낸다. 그래서 목록에서 아예 뺀다.
    // 상담 프롬프트에 확률이 안 실리므로 상담자는 저/고를 판단할 재료가 없다.
    // 그래서 코드가 대신 걸러 안 맞는 칩은 목록에 보이지도 않게 한다.
    @Test
    @DisplayName("진단이 없거나 대역이 안 맞으면 후보 목록에서 뺀다")
    void catalogBlockGatesByBand() {
        assertThat(chipStore.catalogBlock(null)).contains("CONTACT_NOW").doesNotContain("DIAG_LOW");
        assertThat(chipStore.catalogBlock(30)).contains("DIAG_LOW");   // 낮음
        assertThat(chipStore.catalogBlock(55)).doesNotContain("DIAG_LOW"); // 보통
        assertThat(chipStore.catalogBlock(80)).doesNotContain("DIAG_LOW"); // 높음
        assertThat(chipStore.allViews(null)).noneMatch(v -> v.id().equals("DIAG_LOW"));
        assertThat(chipStore.allViews(30)).anyMatch(v -> v.id().equals("DIAG_LOW"));
    }

    // 상담 답변 꼬리에서 뽑는다. 별도 호출로 나눴을 때는 답변 저장 뒤 13~17초가 더 걸렸다.
    @Test
    @DisplayName("답변 꼬리에서 id와 재작성 문장을 뽑는다")
    void parsesTail() {
        String json = """
                [{"id":"CONTACT_NOW","label":"토요일 전에 먼저 연락해도 될까요?"},
                 {"id":"UPDATE_EVENT"}]""";

        assertThat(chipStore.parseTail(json, 30, true, 30, 3))
                .containsExactly(new SuggestedChip("CONTACT_NOW", "토요일 전에 먼저 연락해도 될까요?"),
                        new SuggestedChip("UPDATE_EVENT", null));
    }

    // 라벨은 누르면 그대로 유저 말풍선이 된다. 길이와 빈 값은 코드가 막고,
    // 없는 사실을 지어냈는지는 chip-inline 규칙이 맡는다.
    @Test
    @DisplayName("카탈로그 밖 id, 중복, 못 쓸 라벨은 걸러낸다")
    void tailValidation() {
        String json = """
                [{"id":"지어낸칩"},
                 {"id":"CONTACT_NOW","label":"   "},
                 {"id":"CONTACT_NOW","label":"중복"},
                 {"id":"UPDATE_EVENT","label":"서른 자를 훌쩍 넘겨서 칩 한 줄에 도저히 들어가지 않는 아주 긴 문장"}]""";

        assertThat(chipStore.parseTail(json, 30, true, 30, 3))
                .containsExactly(new SuggestedChip("CONTACT_NOW", null),
                        new SuggestedChip("UPDATE_EVENT", null));
    }

    @Test
    @DisplayName("재작성을 끄면 문장을 버리고 카탈로그 원문을 쓴다")
    void tailRespectsRewriteToggle() {
        String json = "[{\"id\":\"CONTACT_NOW\",\"label\":\"다시 쓴 문장\"}]";

        assertThat(chipStore.parseTail(json, 30, false, 30, 3))
                .containsExactly(new SuggestedChip("CONTACT_NOW", null));
        assertThat(chipStore.parseTail(null, 30, true, 30, 3)).isEmpty();
        assertThat(chipStore.parseTail("깨진 JSON", 30, true, 30, 3)).isEmpty();
    }

    // 목록에서 뺐는데도 모델이 써내는 경우가 있다. 통과시키면 읽을 데이터 없이 지어낸다.
    // 목록에서 뺐는데도 모델이 써내는 경우가 있다. 통과시키면 읽을 데이터 없이,
    // 혹은 안 맞는 대역으로 결과를 설명하게 된다.
    @Test
    @DisplayName("목록에서 뺐는데도 써낸 칩은 꼬리 파싱에서 버린다")
    void tailDropsChipThatWasNotOffered() {
        String json = "[{\"id\":\"DIAG_LOW\"},{\"id\":\"CONTACT_NOW\"}]";

        assertThat(chipStore.parseTail(json, null, true, 30, 3))
                .extracting(SuggestedChip::id).containsExactly("CONTACT_NOW");
        assertThat(chipStore.parseTail(json, 80, true, 30, 3))   // 높은 판에 DIAG_LOW
                .extracting(SuggestedChip::id).containsExactly("CONTACT_NOW");
        assertThat(chipStore.parseTail(json, 30, true, 30, 3))
                .extracting(SuggestedChip::id).containsExactly("DIAG_LOW", "CONTACT_NOW");
    }

    @Test
    @DisplayName("파일이 없으면 빈 카탈로그로 뜬다")
    void missingFilesLoadEmpty() {
        ChipStore empty = new ChipStore(new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(empty, "menuFile", dir.resolve("없음.yml").toString());
        ReflectionTestUtils.setField(empty, "modulesFile", dir.resolve("없음2.yml").toString());
        ReflectionTestUtils.invokeMethod(empty, "load");

        assertThat(empty.all()).isEmpty();
        assertThat(empty.find("CONTACT_NOW")).isNull();
    }
}
