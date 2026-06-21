package com.threeam.match.service;

import com.threeam.match.CaseStore;
import com.threeam.match.MatchBand;
import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// LLM에게 넘길 후보를 고른다. 전체 상위 N을 뽑으면 안 되는 이유: 무연락 유저에겐 실패 사례가
// 연락 상태로 유리해서 상위 열 건이 전부 실패로 차고, 그 목록에서 "성공 두 건"을 요구하면
// LLM이 줄 게 없다. 그래서 결과별로 따로 줄을 세운다.
//
// 하한(MIN_SCORE)은 후보 단계에서도 그대로 건다. 후보를 채우려고 하한을 풀면 LLM이 "닮았다고
// 말하기 민망한" 것들 중에서 억지로 고르게 되고, 그건 하한을 둔 이유를 없애는 것이다.
// 버킷이 모자라면 모자란 채로 넘긴다.
@Component
@RequiredArgsConstructor
public class CaseCandidates {

    // 후보 풀. 뽑아야 할 장수(최대 2)의 서너 배면 LLM이 고를 여지가 충분하다.
    // 더 늘려도 뒤로 갈수록 점수가 급히 떨어져 읽는 값보다 토큰 값이 커진다.
    private static final int POOL_SUCCESS = 8;
    private static final int POOL_FAIL = 6;

    private final CaseStore caseStore;
    private final CaseScorer scorer;

    public record Pool(List<ReunionCase> successes, List<ReunionCase> failures) {

        public List<ReunionCase> all() {
            return java.util.stream.Stream.concat(successes.stream(), failures.stream()).toList();
        }

        public boolean isEmpty() {
            return successes.isEmpty() && failures.isEmpty();
        }
    }

    public Pool of(StoryMatchProfile profile, MatchBand band) {
        return new Pool(
                top(profile, "성공", band.successMax() > 0 ? POOL_SUCCESS : 0),
                top(profile, "실패", band.failMax() > 0 ? POOL_FAIL : 0));
    }

    private List<ReunionCase> top(StoryMatchProfile profile, String outcome, int limit) {
        if (limit == 0) {
            return List.of();
        }
        // 동점은 id로 갈라 매번 같은 후보를 넘긴다 — 후보가 흔들리면 저장된 선택을 재현할 수 없다.
        return caseStore.all().stream()
                .filter(target -> outcome.equals(target.getOutcome()))
                .map(target -> new Scored(target, scorer.score(profile, target)))
                .filter(scored -> scored.score() >= CaseScorer.MIN_SCORE)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(scored -> scored.target().getId()))
                .limit(limit)
                .map(Scored::target)
                .toList();
    }

    private record Scored(ReunionCase target, int score) {
    }
}
