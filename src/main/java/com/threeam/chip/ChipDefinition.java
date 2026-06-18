package com.threeam.chip;

// chip-menu.yml 한 줄. 추천기에 나가는 것(selectorDescription, availableWhen)과
// 클릭 뒤에야 쓰이는 것(module)이 한 객체에 같이 있지만, 프롬프트로 나갈 때는 나뉜다 —
// 추천기는 40개 설명만 보고, 모듈 전문은 실제로 눌린 칩 하나에만 실린다.
public record ChipDefinition(
        String id,
        String label,
        String module,
        String selectorDescription,
        String requiresBand,      // LOW/HIGH면 그 대역에서만 후보에 넣는다. 비면 제한 없음
        ChipInteraction interaction,
        String inputPreset        // INPUT일 때만. DIRECT면 null
) {

    // 추천기 프롬프트에 실리는 한 줄. label을 함께 주는 이유는 설명만으로는 같은 축의 칩끼리
    // 구분이 흐려서다(연락 여부와 연락 시점).
    public String selectorLine() {
        StringBuilder line = new StringBuilder("- ").append(id)
                .append(" | ").append(label)
                .append(" | ").append(selectorDescription);
        return line.toString();
    }
}
