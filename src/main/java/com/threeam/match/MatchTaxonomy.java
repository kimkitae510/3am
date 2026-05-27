package com.threeam.match;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 사례와 유저 프로필이 함께 쓰는 분류 사전(분류체계.md). 이 클래스가 유일한 출처다 —
// 프롬프트의 허용 목록과 파싱의 검증이 같은 상수를 봐야 어휘가 어긋나지 않는다.
// LLM에게 자유 서술을 시키면 "여사친 문제" 같은 말이 나와 사례의 "여사친남사친"과 안 겹치고,
// 서브태그 겹침은 매칭의 1순위 신호라 그 순간 매칭이 통째로 죽는다.
public final class MatchTaxonomy {

    private MatchTaxonomy() {
    }

    public static final List<String> REASONS = List.of(
            "본인과실", "상대과실", "외도", "환승이별", "권태기", "애정식음",
            "성격차이", "가치관차이", "장거리", "외부상황", "잦은싸움", "미상");

    // 본인과실, 상대과실이 공유하는 행동 풀. 방향("누구 잘못")은 태그가 아니라 reason이 가른다.
    private static final List<String> FAULT_BEHAVIORS = List.of(
            "무심소홀", "연락문제", "우선순위낮음", "표현부족", "질투의심", "SNS감시", "집착의존",
            "술버릇", "약속펑크", "거짓말신뢰", "화풀이폭언", "벌주기", "회피잠수", "통제지적",
            "가스라이팅", "폭력", "스킨십강요", "성적대상화", "여사친남사친", "감정쓰레기통",
            "전애인비교", "과거집착", "폰중독", "자격지심", "게임중독", "도박빚", "돈문제");

    // 대상 없이 '태도'만 가리키는 태그. 대부분의 사연에 붙어서 겹쳐도 사연을 특정하지 못한다 —
    // 거짓말신뢰는 코인 은닉과 랜챗 은닉을 같은 방아쇠로 만든다(실측: 랜챗 사연에 코인 사례가
    // 최상위). 그래서 이 태그들의 주-주 일치는 구체 태그보다 낮게 센다(CaseScorer).
    // 실측으로 자라는 목록이다 — 어느 사연에나 붙는 태그가 또 발견되면 추가한다.
    public static final Set<String> GENERIC_SUB_REASONS = Set.of(
            "거짓말신뢰", "무심소홀", "연락문제", "우선순위낮음", "표현부족", "감정누적");

    // 대분류에 속한 태그들. 주석 그룹으로만 있던 갈래를 코드로 올린 것 — 이게 없으면
    // 대분류가 갈린 순간 30점을 통째로 잃어서, 루브릭이 "경계 케이스는 양쪽 프레임 태그를
    // 함께 실어라"고 시킨 보람이 사라진다(실측: 소개팅앱을 실었는데도 외도 사례가 밀렸다).
    private static final Map<String, List<String>> TAGS_BY_REASON = new LinkedHashMap<>();

    static {
        TAGS_BY_REASON.put("외도", List.of(
                "내가바람", "상대가바람", "감정적바람", "소개팅앱", "직장동료"));
        TAGS_BY_REASON.put("환승이별", List.of(
                "상대환승", "지인친구와환승", "환승의심"));
        TAGS_BY_REASON.put("권태기", List.of(
                "장기연애권태", "매너리즘반복일상"));
        TAGS_BY_REASON.put("애정식음", List.of(
                "마음변함", "설렘없음", "이유없이식음"));
        TAGS_BY_REASON.put("성격차이", List.of(
                "계획즉흥", "감정처리", "데이트성향", "표현방식", "스킨십속도", "갈등회피",
                "연락빈도차이", "개인시간", "소개안함비공개", "미신의존", "관계정의"));
        TAGS_BY_REASON.put("가치관차이", List.of(
                "결혼타이밍", "돈소비경제관", "종교", "일커리어", "자녀계획", "거주지역",
                "가족관계", "결혼준비파혼"));
        TAGS_BY_REASON.put("장거리", List.of(
                "거리지침", "유학워홀", "발령이직", "교환학생", "시차", "만남횟수부족"));
        TAGS_BY_REASON.put("외부상황", List.of(
                "취업취준", "부모반대", "사내연애소문", "우울증건강", "군대", "시험", "사업",
                "경제적어려움", "가족문제"));
        // 감정누적은 여기 없다 — 싸움 없이도 감정은 쌓이고(말 안 하고 혼자 쌓다 끊는 판이 그렇다),
        // 갈래에 두면 그 태그 하나로 무다툼 사연이 싸움 이별로 분류돼 온오프 사례가 붙는다(실측).
        TAGS_BY_REASON.put("잦은싸움", List.of(
                "싸움방식", "상처주는말", "사소한반복"));
        TAGS_BY_REASON.put("미상", List.of(
                "이유안알려줌", "잠수이별연락두절", "자연소멸"));
    }

    public static final Set<String> SUB_REASONS;

    // 태그 → 그 태그가 가리키는 대분류들. 사전이 한 곳이라 어긋날 일이 없다.
    private static final Map<String, Set<String>> REASONS_BY_TAG;

    static {
        Set<String> all = new LinkedHashSet<>(FAULT_BEHAVIORS);
        Map<String, Set<String>> byTag = new LinkedHashMap<>();
        TAGS_BY_REASON.forEach((reason, tags) -> tags.forEach(tag -> {
            all.add(tag);
            byTag.computeIfAbsent(tag, key -> new LinkedHashSet<>()).add(reason);
        }));
        SUB_REASONS = Set.copyOf(all);
        REASONS_BY_TAG = Map.copyOf(byTag);
    }

    // 이 태그가 가리키는 대분류들(없으면 빈 집합 — 행동 풀 태그는 갈래를 가리지 못한다).
    public static Set<String> reasonsOfTag(String tag) {
        return REASONS_BY_TAG.getOrDefault(tag, Set.of());
    }

    public static boolean isGeneric(String tag) {
        return GENERIC_SUB_REASONS.contains(tag);
    }

    // 나떠밀림: 말은 유저가 꺼냈지만 상대가 밀어붙여 만든 이별("시간 갖자", 식은 티 뒤에
    // 유저가 통보만 한 판). 겉 dumper는 나인데 구도는 상대 통보라, 값을 가르지 않으면
    // 진짜 자기 마음이 식어서 찬 사례가 최상위로 매칭되는 오독이 난다(실측).
    public static final List<String> DUMPERS = List.of("나", "상대", "나떠밀림", "미상");
    // 미상: 사례 데이터가 이미 쓰는 값("모름"은 "잘못 없음"과 다르다). 미상끼리 일치는
    // dumper처럼 가점하지 않는다 — 둘 다 모른다는 건 닮은 게 아니다(CaseScorer).
    public static final List<String> FAULTS = List.of("나", "상대", "양쪽", "없음", "미상");
    public static final List<String> CONTACT_STATES =
            List.of("무연락", "차단", "읽씹", "상대가연락", "연락중");

    // 사전에 없는 값은 사례와 겹칠 수 없으니 저장할 값어치가 없다 — 파싱 단계에서 버린다.
    public static boolean isReason(String value) {
        return value != null && REASONS.contains(value);
    }

    public static boolean isSubReason(String value) {
        return value != null && SUB_REASONS.contains(value);
    }
}
