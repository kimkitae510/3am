package com.threeam.assessment.dto;

import java.util.List;

// 관계 심리 해석 층 — 확률 계산에 쓰지 않는 "관계 이해용" 판정(애착 경향, 관계 패턴, 욕구 충돌).
// 확률 요인과 섞이면 "회피형이라 -n" 같은 계산이 생기므로 요인 체계 밖의 별도 구조로 둔다.
// 라벨은 유저에게 그대로 보이는 어휘라 백엔드 enum 대신 사전 리스트로 검증만 한다 —
// 화면 분기가 없고(표시 전용), 어휘 조정이 잦을 초기라 enum 개편 비용을 피한다.
// 저장은 assessments.relationship_psychology TEXT 컬럼에 JSON 통짜로(컨버터 참고).
public record RelationshipPsychology(
        Attachment attachment,
        PatternItem interactionPattern,
        NeedConflict needConflict) {

    public static final List<String> ATTACHMENT_LABELS =
            List.of("안정형", "불안형", "회피형", "혼재", "판단보류");

    public static final List<String> PATTERN_LABELS =
            List.of("추구-회피", "요구-철회", "비난-방어", "확인-거리두기",
                    "갈등회피-폭발", "과기능-저기능", "상호격화", "뚜렷하지않음");

    public static final List<String> CONFIDENCE_LABELS = List.of("높음", "중간", "낮음");

    // 판단이 안 서는 판의 값. 화면과 프롬프트 주입에서 이 값들은 행을 만들지 않는다 —
    // "모르겠다"를 카드나 앵커로 만들면 소음이고, 다음 진단을 그 값에 묶는다.
    public static final String ATTACHMENT_UNDECIDED = "판단보류";
    public static final String PATTERN_UNDECIDED = "뚜렷하지않음";

    // 애착 경향. 유저와 상대를 각각 본다 — 한쪽만 판정하면 롱디처럼 양쪽이 다른 판
    // (유저 불안 활성화 + 상대 회피 전략)을 담을 수 없다.
    public record Attachment(Style user, Style partner, String description) {
    }

    public record Style(String label, String confidence) {
    }

    // 두 사람의 행동이 서로를 자극하며 만든 반복 구조. 개인 성향이 아니라 순환이 관찰될 때만.
    public record PatternItem(String label, String confidence, String description) {
    }

    // 각자가 관계에서 가장 필요로 한 것 하나씩. left가 유저, right가 상대.
    public record NeedConflict(String left, String right, String description) {
    }
}
