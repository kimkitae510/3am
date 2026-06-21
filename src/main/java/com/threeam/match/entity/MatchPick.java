package com.threeam.match.entity;

import java.util.List;

// LLM이 고른 사례 한 건과 그 해설. caseId는 사례 파일의 배열 순서(CaseStore가 부여한 id)다 —
// 사례를 중간에 끼워 넣으면 저장된 옛 선택이 다른 사례를 가리키게 되므로 추가는 맨 뒤에만 한다.
//
// tags, similarity, reading 전부 LLM이 쓴다. 태그를 규칙에서 LLM으로 넘긴 이유는 규칙이
// 구조 필드만 봐서 "이별의 결정적 계기" 같은 축 이름밖에 못 뽑기 때문이다 — 본문을 읽는 쪽이
// "새벽 카톡"처럼 실제로 겹친 지점을 짚는다.
//
// 대신 규칙이 화이트리스트로 막아주던 자리(폭력, 성적대상화 같은 적나라한 값이 남의 카드에
// 이름표로 박히는 것)를 프롬프트가 맡는다. 코드에도 최후 방어선을 한 겹 둔다(PaidMatchService).
public record MatchPick(Long caseId, List<String> tags, String similarity, String reading) {

    public MatchPick {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    // summary: 고른 사례들을 가로질러 읽는 한 줄. 카드마다 따로 붙는 similarity, reading과
    // 달리 "실패한 쪽은 무엇 때문이었고 성공한 쪽은 무엇이 달랐는지"를 견주는 자리다.
    // 카드가 한 장이면 견줄 게 없어 비게 되고, 폴백으로 사례만 채운 판에서도 비운다 —
    // 버려진 카드를 설명하는 문장이 남으면 화면과 어긋난다.
    public record Picks(String summary, List<MatchPick> items) {

        public Picks {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
