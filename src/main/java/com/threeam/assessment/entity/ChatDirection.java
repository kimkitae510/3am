package com.threeam.assessment.entity;

// 이 사연의 대화에서 상담자(채팅)가 재회 가능성을 어느 쪽으로 말했는지. 분석이 대화를 읽고
// 판정한다 — 채팅에게 스스로 숫자를 적게 하는 것보다 정확하다. 그건 억지로 짜낸 숫자지만
// 이건 이미 한 말에 대한 관측이라서다.
// 분석 확률과 대조해 두 판정이 어긋난 사연을 찾는 데만 쓴다. 화면에는 나가지 않는다.
public enum ChatDirection {

    HIGH("높음"),
    MID("중간"),
    LOW("낮음"),
    NONE("언급없음");

    private final String label;

    ChatDirection(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ChatDirection fromLabel(String label) {
        if (label == null) {
            return NONE;
        }
        for (ChatDirection value : values()) {
            if (value.label.equals(label.trim())) {
                return value;
            }
        }
        return NONE;
    }

    public static java.util.List<String> labels() {
        return java.util.Arrays.stream(values()).map(ChatDirection::label).toList();
    }
}
