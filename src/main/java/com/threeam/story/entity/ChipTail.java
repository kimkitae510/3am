package com.threeam.story.entity;

// 상담 답변 맨 끝에 붙는 추천 질문 블록. ---chat-meta---와 같은 자리, 같은 방식이다.
//
//   본문...
//   ---chips---
//   [{"id":"CONTACT_NOW","label":"토요일 전에 먼저 연락해도 될까요?"}, ...]
//
// 칩을 별도 LLM 호출로 뽑던 것을 상담 호출 하나로 합치면서 생겼다. 호출을 나눴을 때는
// 답변이 저장된 뒤 13~17초 있다 칩이 도착해서, 화면이 그동안 "다 됐냐"를 반복해 물어야 했다.
// 답변과 같이 오면 그 폴링이 통째로 사라지고 호출도 절반이 된다.
//
// JSON 모드를 켜지 않는 이유가 핵심이다 — 본체는 어디까지나 상담 답변이다. 꼬리표만 붙이면
// 이 블록이 깨져도 칩만 안 뜨고 답변은 멀쩡하다. 응답 전체에 JSON을 강제했다면 파싱 실패가
// 답변을 인질로 잡는다.
public final class ChipTail {

    public static final String MARKER = "---chips---";

    private ChipTail() {
    }

    // 마커부터 끝까지 잘라낸다. 마커가 없으면 원문 그대로.
    public static String strip(String reply) {
        if (reply == null) {
            return null;
        }
        int at = reply.indexOf(MARKER);
        return at < 0 ? reply : reply.substring(0, at).stripTrailing();
    }

    // 꼬리의 JSON 부분만 돌려준다. 없으면 null — 카탈로그 대조와 라벨 검증은 부르는 쪽이 한다.
    public static String json(String reply) {
        if (reply == null) {
            return null;
        }
        int at = reply.indexOf(MARKER);
        if (at < 0) {
            return null;
        }
        String tail = reply.substring(at + MARKER.length());
        int start = tail.indexOf('[');
        int end = tail.lastIndexOf(']');
        return (start >= 0 && end > start) ? tail.substring(start, end + 1) : null;
    }
}
