package com.threeam.chip;

// INPUT 칩이 띄우는 입력 UI 문구. 칩 수만큼 폼을 만들지 않으려고 프리셋을 공유한다 —
// "상대가 연락했어요", "차단이 풀렸어요"를 나중에 각각 칩으로 쪼개도 프리셋은 하나다.
public record ChipInputPreset(
        String title,
        String placeholder,
        String helper,
        String submitLabel
) {}
