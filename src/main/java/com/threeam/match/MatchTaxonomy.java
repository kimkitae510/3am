package com.threeam.match;

import java.util.LinkedHashSet;
import java.util.List;
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
            "가스라이팅", "폭력", "여사친남사친", "감정쓰레기통", "전애인비교", "과거집착",
            "폰중독", "자격지심", "게임중독", "도박빚", "돈문제");

    private static final List<String> OTHERS = List.of(
            // 외도, 환승이별
            "내가바람", "상대가바람", "감정적바람", "소개팅앱", "직장동료",
            "상대환승", "지인친구와환승", "환승의심",
            // 권태기, 애정식음
            "장기연애권태", "매너리즘반복일상", "마음변함", "설렘없음", "이유없이식음",
            // 성격차이
            "계획즉흥", "감정처리", "데이트성향", "표현방식", "스킨십속도", "갈등회피",
            "연락빈도차이", "개인시간", "소개안함비공개", "미신의존",
            // 가치관차이
            "결혼타이밍", "돈소비경제관", "종교", "일커리어", "자녀계획", "거주지역",
            "가족관계", "결혼준비파혼",
            // 장거리
            "거리지침", "유학워홀", "발령이직", "교환학생", "시차", "만남횟수부족",
            // 외부상황
            "취업취준", "부모반대", "사내연애소문", "우울증건강", "군대", "시험", "사업",
            "경제적어려움", "가족문제",
            // 잦은싸움
            "싸움방식", "상처주는말", "사소한반복", "감정누적",
            // 미상
            "이유안알려줌", "잠수이별연락두절", "자연소멸");

    public static final Set<String> SUB_REASONS;

    static {
        Set<String> all = new LinkedHashSet<>(FAULT_BEHAVIORS);
        all.addAll(OTHERS);
        SUB_REASONS = Set.copyOf(all);
    }

    public static final List<String> DUMPERS = List.of("나", "상대", "미상");
    public static final List<String> FAULTS = List.of("나", "상대", "양쪽", "없음");
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
