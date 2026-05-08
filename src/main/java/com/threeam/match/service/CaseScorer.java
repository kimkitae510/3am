package com.threeam.match.service;

import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

// 유저 프로필과 사례 한 건의 닮은 정도를 점수로 만든다(분류체계.md 가중치 티어).
// 티어를 쓰는 이유: 겹치는 항목 수를 세면 나이대와 이별 사유가 같은 무게가 돼
// "20대 후반이라 비슷한 사례"가 뽑힌다. 무엇이 닮았느냐가 몇 개 닮았느냐보다 중요하다.
@Component
public class CaseScorer {

    // T1 서브태그 겹침 — 순서가 뜻을 가진다. 주(0번)는 이별을 실제로 당긴 방아쇠이고
    // 부는 밑에 깔린 요인이라, 주끼리 맞는 것과 부끼리 스치는 것은 같은 사건이 아니다.
    private static final int PRIMARY_BOTH = 50;      // 방아쇠가 같음
    // 절반(25)으로 두면 엇갈린 겹침 두 개가 방아쇠 일치 하나와 동점이 된다 — 겹친 태그 수가
    // 방아쇠 일치를 이기는 순간 티어를 나눈 뜻이 사라지므로, 두 번 엇갈려도 못 넘게 20으로 둔다.
    private static final int PRIMARY_CROSS = 20;     // 한쪽의 방아쇠가 다른 쪽 밑바닥에 깔림
    private static final int SECONDARY_BOTH = 10;    // 배경끼리만 겹침

    // T2 핵심 — 대분류와 이별의 구도(누가 통보했나, 누구 잘못인가)
    private static final int REASON = 30;
    private static final int DUMPER = 15;
    private static final int FAULT = 10;

    // T3 상황 — 지금 어떤 상태인가
    private static final int CONTACT_STATE = 12;
    private static final int REPEAT_BREAKUP = 8;

    // T4 부수 — 배경. 이것만 맞아선 "비슷한 사례"가 되지 않게 낮게 둔다.
    private static final int PERIOD_BUCKET = 6;
    private static final int DATING_BUCKET = 4;
    private static final int AGE_GROUP = 3;

    // 이 아래는 "닮았다"고 말하기 민망한 수준이라 안 보여준다. 억지로 채운 사례가 섞이면
    // 제대로 뽑힌 앞의 사례까지 못 믿게 된다. 대분류(30)만 같고 나머지가 다 어긋난 정도가 경계.
    public static final int MIN_SCORE = 45;

    public int score(StoryMatchProfile profile, ReunionCase target) {
        // 방향 게이트: 통보 주체나 과실이 '나'와 '상대'로 명시 반대인 사례는 태그가 아무리
        // 겹쳐도 남의 이야기다(실측: 유저가 마음이 식어 찬 사연에 "상대가 식어서 나를 찼다"
        // 사례가 최상위로 매칭됨 — 같은 애정식음이어도 겪는 자리가 반대면 공감이 아니라 오독을
        // 만든다). 합의, 미상, 양쪽, 없음은 반대가 아니므로 게이트에 안 걸린다.
        if (opposed(profile.getDumper(), target.getDumper())
                || opposed(profile.getFault(), target.getFault())) {
            return 0;
        }
        return subReasonScore(profile.subReasonList(), target.subReasonList())
                + tierTwo(profile, target)
                + tierThree(profile, target)
                + tierFour(profile, target);
    }

    // '나'와 '상대'가 서로 맞부딪히는 경우만 반대다.
    private boolean opposed(String mine, String theirs) {
        return ("나".equals(mine) && "상대".equals(theirs))
                || ("상대".equals(mine) && "나".equals(theirs));
    }

    // 순서 있는 두 목록의 겹침을 위치까지 보고 점수화한다. 가장 센 겹침 하나만 인정하지 않고
    // 모두 합산한다 — 주도 같고 부도 같은 사례는 주만 같은 사례보다 실제로 더 닮았다.
    private int subReasonScore(List<String> mine, List<String> theirs) {
        if (mine.isEmpty() || theirs.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (int i = 0; i < mine.size(); i++) {
            int j = theirs.indexOf(mine.get(i));
            if (j < 0) {
                continue;
            }
            boolean minePrimary = i == 0;
            boolean theirsPrimary = j == 0;
            if (minePrimary && theirsPrimary) {
                score += PRIMARY_BOTH;
            } else if (minePrimary || theirsPrimary) {
                score += PRIMARY_CROSS;
            } else {
                score += SECONDARY_BOTH;
            }
        }
        return score;
    }

    private int tierTwo(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        score += same(profile.getReason(), target.getReason()) ? REASON : 0;
        // 미상끼리 맞은 건 "둘 다 모른다"일 뿐 닮은 게 아니다.
        if (!"미상".equals(profile.getDumper()) && same(profile.getDumper(), target.getDumper())) {
            score += DUMPER;
        }
        score += same(profile.getFault(), target.getFault()) ? FAULT : 0;
        return score;
    }

    private int tierThree(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        score += same(profile.getContactState(), target.getContactState()) ? CONTACT_STATE : 0;
        // 온오프를 겪은 사이끼리만 인정한다 — 둘 다 아닌 건 대다수라 변별력이 없다.
        if (Boolean.TRUE.equals(profile.getRepeatBreakup())
                && Boolean.TRUE.equals(target.getRepeatBreakup())) {
            score += REPEAT_BREAKUP;
        }
        return score;
    }

    private int tierFour(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        score += sameBucket(profile.getMonthsSinceBreakup(), target.getMonthsSinceBreakup(),
                PERIOD_BUCKETS) ? PERIOD_BUCKET : 0;
        score += sameBucket(profile.getDatingMonths(), target.getDatingMonths(),
                DATING_BUCKETS) ? DATING_BUCKET : 0;
        score += same(profile.getAgeGroup(), target.getAgeGroup()) ? AGE_GROUP : 0;
        return score;
    }

    // 이별 경과: 한 달 안, 석 달 안, 반년 안, 그 뒤. 한 달과 두 달은 다른 시기지만
    // 열 달과 열한 달은 사실상 같은 시기라 뒤로 갈수록 넓게 잡는다.
    private static final int[] PERIOD_BUCKETS = {1, 3, 6};

    // 연애 기간: 반년 안, 1년 안, 2년 안, 그 뒤.
    private static final int[] DATING_BUCKETS = {6, 12, 24};

    private boolean sameBucket(Integer mine, Integer theirs, int[] edges) {
        return mine != null && theirs != null && bucket(mine, edges) == bucket(theirs, edges);
    }

    private int bucket(int value, int[] edges) {
        for (int i = 0; i < edges.length; i++) {
            if (value <= edges[i]) {
                return i;
            }
        }
        return edges.length;
    }

    private boolean same(String mine, String theirs) {
        return mine != null && !mine.isBlank() && Objects.equals(mine, theirs);
    }
}
