package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.match.MatchBand;
import com.threeam.match.entity.MatchPick;
import com.threeam.match.entity.ReunionCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

// settle()만 검증한다 — 스키마가 caseId는 못 박아 주지만 구성(성공 몇, 실패 몇)은 표현할 수
// 없어서 여기가 유일한 방어선이다. 나머지 경로(쿼터, 저장)는 통합에 가까워 여기서 안 본다.
class PaidMatchSettleTest {

    private final PaidMatchService service =
            new PaidMatchService(null, null, null, null, null, null, null, null);

    private ReunionCase reunionCase(long id, String outcome) {
        ReunionCase target = BeanUtils.instantiateClass(ReunionCase.class);
        ReflectionTestUtils.setField(target, "id", id);
        ReflectionTestUtils.setField(target, "outcome", outcome);
        return target;
    }

    private CaseCandidates.Pool pool() {
        return new CaseCandidates.Pool(
                List.of(reunionCase(1, "성공"), reunionCase(2, "성공"), reunionCase(3, "성공")),
                List.of(reunionCase(11, "실패"), reunionCase(12, "실패")));
    }

    private MatchPick pick(long id) {
        return new MatchPick(id, List.of("겹친 지점"), "겹치는 지점", "추측");
    }

    private MatchPick.Picks picked(MatchPick... items) {
        return new MatchPick.Picks("실패한 쪽은 A 때문이고 성공한 쪽은 B가 달랐다", List.of(items));
    }

    @Test
    @DisplayName("상한을 넘는 성공 선택은 앞에서부터 남기고 자른다")
    void trimsSuccessesBeyondBandCap() {
        MatchPick.Picks settled = service.settle(
                picked(pick(1), pick(2), pick(3)), pool(), MatchBand.LOW);

        assertThat(settled.items()).extracting(MatchPick::caseId).containsExactly(1L);
        // 카드가 한 장이면 견줄 게 없어 요약을 비운다.
        assertThat(settled.summary()).isNull();
    }

    // 높은 대역은 실패를 안 싣는다 — 확률이 이미 말한 것을 카드로 뒤집지 않는다.
    @Test
    @DisplayName("높은 대역에서 실패를 고르면 버린다")
    void dropsFailureInHighBand() {
        MatchPick.Picks settled = service.settle(
                picked(pick(1), pick(11), pick(2)), pool(), MatchBand.HIGH);

        assertThat(settled.items()).extracting(MatchPick::caseId).containsExactly(1L, 2L);
        assertThat(settled.summary()).isNotBlank();
    }

    @Test
    @DisplayName("후보에 없는 id와 중복 선택은 버린다")
    void dropsUnknownAndDuplicateIds() {
        MatchPick.Picks settled = service.settle(
                picked(pick(99), pick(1), pick(1), pick(11)), pool(), MatchBand.MID);

        assertThat(settled.items()).extracting(MatchPick::caseId).containsExactly(1L, 11L);
    }

    // 유료 화면이 빈 채로 나가는 것보다는 해설 없는 카드가 낫다.
    @Test
    @DisplayName("유효한 선택이 하나도 없으면 점수 순 상위로 채우되 해설은 비운다")
    void fallsBackToTopScoredWithoutCommentary() {
        MatchPick.Picks settled = service.settle(picked(pick(77)), pool(), MatchBand.MID);

        assertThat(settled.items()).extracting(MatchPick::caseId).containsExactly(1L, 2L, 11L);
        assertThat(settled.items()).allSatisfy(pick -> {
            assertThat(pick.similarity()).isNull();
            assertThat(pick.reading()).isNull();
        });
        // 버려진 카드를 설명하는 요약이 남으면 화면과 어긋난다.
        assertThat(settled.summary()).isNull();
    }
}
