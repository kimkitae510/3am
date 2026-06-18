package com.threeam.story.entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// turn-2 답변 맨 끝에 붙는 내부 메타데이터. 유저에게 보이라고 만든 문장이 아니라
// 서버가 읽어 방향을 저장하려고 만든 값이다.
//
//   본문...
//   ---chat-meta---
//   {"reunionDirection":"UNCERTAIN"}
//
// 저장 전에 떼어낸다. 대화 기록에 남기면 두 가지가 따라온다 — 화면에 JSON이 그대로 찍히고,
// 다음 턴 프롬프트에 어시스턴트 발화로 다시 실려 모델이 turn-rest에서도 같은 형식을 이어간다.
// 방향은 이미 답변 본문에 문장으로도 들어가므로, 꼬리를 지워도 다음 턴이 잃는 맥락은 없다.
//
// 표시 단계에서도 한 번 더 떼는 이유는 이 코드가 생기기 전에 저장된 대화 때문이다 —
// 그 행들의 content에는 JSON이 박혀 있고, 기록은 고치지 않는다.
public final class ChatMeta {

    public static final String MARKER = "---chat-meta---";

    // 값만 뽑는다. JSON 파서를 쓰지 않는 이유는 이 한 칸짜리 구조에 파싱 실패라는 실패 모드를
    // 새로 만들 이유가 없어서다 — 모델이 따옴표나 공백을 흘려도 값은 건진다.
    private static final Pattern DIRECTION =
            Pattern.compile("\"?reunionDirection\"?\\s*:\\s*\"?([A-Za-z_]+)\"?");

    private ChatMeta() {
    }

    // 마커부터 끝까지 잘라낸다. 마커가 없으면 원문 그대로.
    public static String strip(String reply) {
        if (reply == null) {
            return null;
        }
        int at = reply.indexOf(MARKER);
        return at < 0 ? reply : reply.substring(0, at).stripTrailing();
    }

    // 못 읽으면 null. 사전 밖 값도 null이다 — 없는 방향을 지어내느니 지난 값을 유지하는 편이 낫다.
    public static ReunionDirection direction(String reply) {
        if (reply == null) {
            return null;
        }
        int at = reply.indexOf(MARKER);
        if (at < 0) {
            return null;
        }
        Matcher matcher = DIRECTION.matcher(reply.substring(at + MARKER.length()));
        if (!matcher.find()) {
            return null;
        }
        try {
            return ReunionDirection.valueOf(matcher.group(1).toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
