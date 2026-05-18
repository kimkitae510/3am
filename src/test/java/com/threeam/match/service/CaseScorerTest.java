package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

class CaseScorerTest {

    private final CaseScorer scorer = new CaseScorer();

    private StoryMatchProfile profile(String reason, String subReasons) {
        return StoryMatchProfile.builder()
                .storyId(1L)
                .reason(reason)
                .subReasons(subReasons)
                .build();
    }

    private ReunionCase reunionCase(String reason, String subReasons) {
        ReunionCase target = BeanUtils.instantiateClass(ReunionCase.class);
        ReflectionTestUtils.setField(target, "id", 1L);
        ReflectionTestUtils.setField(target, "reason", reason);
        ReflectionTestUtils.setField(target, "subReasons",
                subReasons == null ? null : Arrays.asList(subReasons.split(",")));
        return target;
    }

    @Test
    @DisplayName("서브태그 - 주끼리 같은 쪽이 주와 부가 엇갈린 쪽보다 높다")
    void primaryMatchOutranksCrossMatch() {
        StoryMatchProfile mine = profile("본인과실", "질투의심,무심소홀");

        int bothPrimary = scorer.score(mine, reunionCase("본인과실", "질투의심"));
        int crossed = scorer.score(mine, reunionCase("본인과실", "무심소홀,질투의심"));

        // 방아쇠가 같은 사례가 배경만 겹치는 사례보다 실제로 더 닮았다.
        assertThat(bothPrimary).isGreaterThan(crossed);
    }

    @Test
    @DisplayName("서브태그 - 부끼리만 겹치면 주끼리 겹칠 때보다 크게 낮다")
    void secondaryOnlyScoresLowest() {
        StoryMatchProfile mine = profile("성격차이", "감정처리,갈등회피");

        int primary = scorer.score(mine, reunionCase("성격차이", "감정처리"));
        int secondaryOnly = scorer.score(mine, reunionCase("성격차이", "계획즉흥,갈등회피"));

        assertThat(primary).isGreaterThan(secondaryOnly);
    }

    @Test
    @DisplayName("서브태그 - 주와 부가 모두 겹치면 주만 겹칠 때보다 높다(겹침을 합산한다)")
    void overlapsAccumulate() {
        StoryMatchProfile mine = profile("본인과실", "질투의심,무심소홀");

        int both = scorer.score(mine, reunionCase("본인과실", "질투의심,무심소홀"));
        int primaryOnly = scorer.score(mine, reunionCase("본인과실", "질투의심,술버릇"));

        assertThat(both).isGreaterThan(primaryOnly);
    }

    @Test
    @DisplayName("대분류만 같고 서브태그가 전부 어긋나면 임계에 못 미친다")
    void reasonAloneIsNotSimilarEnough() {
        int score = scorer.score(profile("장거리", "거리지침"), reunionCase("장거리", "시차"));

        assertThat(score).isLessThan(CaseScorer.MIN_SCORE);
    }

    @Test
    @DisplayName("dumper가 미상끼리 맞은 건 닮은 것으로 세지 않는다")
    void unknownDumperIsNotSimilarity() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("미상").dumper("미상").build();
        ReunionCase target = reunionCase("미상", null);
        ReflectionTestUtils.setField(target, "dumper", "미상");

        // 대분류(30)만 인정되고 dumper는 빠진다 — 둘 다 모른다는 건 공통점이 아니다.
        assertThat(scorer.score(mine, target)).isEqualTo(30);
    }

    @Test
    @DisplayName("떠밀린 통보는 진짜 자기가 찬 사례와 매칭되지 않는다(방향 게이트)")
    void pushedDumperIsOpposedToGenuineSelfDumper() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("애정식음").subReasons("마음변함").dumper("나떠밀림").build();
        ReunionCase target = reunionCase("애정식음", "마음변함");
        ReflectionTestUtils.setField(target, "dumper", "나");

        // 태그가 아무리 겹쳐도 겪는 자리가 반대면 남의 이야기다.
        assertThat(scorer.score(mine, target)).isZero();
    }

    @Test
    @DisplayName("떠밀린 통보는 상대 통보 사례와 같은 쪽으로 가점된다(정확 일치보다는 낮게)")
    void pushedDumperIsKindredToPartnerDumper() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("애정식음").dumper("나떠밀림").build();

        ReunionCase partner = reunionCase("애정식음", null);
        ReflectionTestUtils.setField(partner, "dumper", "상대");
        ReunionCase exact = reunionCase("애정식음", null);
        ReflectionTestUtils.setField(exact, "dumper", "나떠밀림");
        ReunionCase silent = reunionCase("애정식음", null);

        int partnerScore = scorer.score(mine, partner);
        int exactScore = scorer.score(mine, exact);
        int silentScore = scorer.score(mine, silent);

        assertThat(partnerScore).isGreaterThan(silentScore);
        assertThat(exactScore).isGreaterThan(partnerScore);
        assertThat(scorer.matchedAspects(mine, partner)).contains("이별을 원한 쪽");
    }

    @Test
    @DisplayName("소재가 같은 사례가 이긴다 — 범용 태그(거짓말신뢰)만 겹치는 사례를 대분류 교차가 넘는다")
    void concreteTopicOutranksGenericTagMatch() {
        // 실측: 랜챗 은닉으로 차인 사연에 코인 은닉 파혼 사례가 최상위로 왔다.
        // 유저는 본인과실로 잡혔고 소개팅앱 태그를 함께 실었는데, 대분류가 갈려 30점을 잃고
        // 범용 태그 주-주 일치 50점이 코인 사례를 밀어 올렸다.
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("거짓말신뢰,소개팅앱,감정쓰레기통")
                .dumper("상대").fault("나").contactState("차단").build();

        ReunionCase money = reunionCase("본인과실", "거짓말신뢰,돈문제");
        ReflectionTestUtils.setField(money, "dumper", "상대");
        ReflectionTestUtils.setField(money, "fault", "나");
        ReflectionTestUtils.setField(money, "contactState", "연락중");

        ReunionCase dating = reunionCase("외도", "소개팅앱,거짓말신뢰");
        ReflectionTestUtils.setField(dating, "dumper", "상대");
        ReflectionTestUtils.setField(dating, "fault", "나");
        ReflectionTestUtils.setField(dating, "contactState", "차단");

        // 대분류가 갈렸어도 소개팅앱이 외도를 가리켜 교차 점수를 받고, 범용 태그로 이긴 사례를 넘는다.
        assertThat(scorer.score(mine, dating)).isGreaterThan(scorer.score(mine, money));
        // 라벨은 정확 일치에만 붙인다(최대 3개라 자리도 없다) — 교차는 점수로만 반영된다.
        assertThat(scorer.matchedAspects(mine, dating))
                .contains("이별의 결정적 계기", "헤어지자고 한 쪽", "지금 연락 상태")
                .doesNotContain("이별 사유");
    }

    @Test
    @DisplayName("범용 태그의 방아쇠 일치는 구체 태그의 방아쇠 일치보다 낮다")
    void genericPrimaryScoresLower() {
        int generic = scorer.score(profile("본인과실", "거짓말신뢰"),
                reunionCase("본인과실", "거짓말신뢰"));
        int concrete = scorer.score(profile("본인과실", "도박빚"),
                reunionCase("본인과실", "도박빚"));

        assertThat(concrete).isGreaterThan(generic);
    }

    @Test
    @DisplayName("이별 경과는 버킷으로 본다 - 한 달 안끼리는 같게, 한 달과 넉 달은 다르게")
    void periodComparedByBucket() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("잦은싸움").monthsSinceBreakup(1).build();

        ReunionCase near = reunionCase("잦은싸움", null);
        ReflectionTestUtils.setField(near, "monthsSinceBreakup", 1);
        ReunionCase far = reunionCase("잦은싸움", null);
        ReflectionTestUtils.setField(far, "monthsSinceBreakup", 4);

        assertThat(scorer.score(mine, near)).isGreaterThan(scorer.score(mine, far));
    }
}
