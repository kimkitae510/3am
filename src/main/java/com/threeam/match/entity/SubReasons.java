package com.threeam.match.entity;

import java.util.ArrayList;
import java.util.List;

// 서브태그의 저장 표현(쉼표 문자열)과 계산 표현(순서 있는 목록) 사이를 옮긴다.
// 순서가 의미를 가진다 — 0번이 주(방아쇠), 뒤가 밑에 깔린 요인이라 매칭 점수가 달라진다.
public final class SubReasons {

    // 다 넣으면 변별력이 사라져 상한을 둔다(분류체계.md 원칙 2).
    public static final int MAX = 3;

    private SubReasons() {
    }

    public static List<String> parse(String raw) {
        List<String> tags = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        for (String token : raw.split(",")) {
            String tag = token.trim();
            // 중복은 같은 태그를 두 번 세어 점수를 부풀리므로 걸러낸다.
            if (!tag.isEmpty() && !tags.contains(tag) && tags.size() < MAX) {
                tags.add(tag);
            }
        }
        return tags;
    }

    public static String join(List<String> tags) {
        List<String> parsed = parse(tags == null ? null : String.join(",", tags));
        return parsed.isEmpty() ? null : String.join(",", parsed);
    }
}
