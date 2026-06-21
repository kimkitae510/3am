package com.threeam.match.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.match.dto.SimilarCaseResponse;
import com.threeam.match.dto.SimilarCasesResponse;
import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.CaseStore;
import com.threeam.match.repository.StoryMatchProfileRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 분석이 뽑아둔 프로필로 참조 사례를 찾아준다. LLM을 부르지 않아 쿼터도 없다.
// 사례는 CaseStore가 메모리에 상주시킨 불변 목록이라 요청 경로에서 DB는 프로필 한 행뿐이다.
@Service
@RequiredArgsConstructor
public class MatchService {

    // 뒤로 갈수록 유사도가 급히 떨어진다. 억지로 채운 세 번째 사례가 "이건 내 얘기가
    // 아닌데"가 되면 앞의 둘까지 못 믿게 되므로 두 건만 보여준다.
    private static final int TOP_N = 2;

    private final StoryRepository storyRepository;
    private final StoryMatchProfileRepository profileRepository;
    private final CaseStore caseStore;
    private final CaseScorer scorer;

    @Transactional(readOnly = true)
    public SimilarCasesResponse findSimilar(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));

        StoryMatchProfile profile = profileRepository.findFirstByStoryIdOrderByIdDesc(storyId).orElse(null);
        if (profile == null || !profile.matchable()) {
            return SimilarCasesResponse.noProfile();
        }

        // 동점은 id로 갈라 매번 같은 순서를 보장한다(새로고침마다 사례가 바뀌면 신뢰가 깎인다).
        List<SimilarCaseResponse> matched = caseStore.all().stream()
                .map(target -> new Scored(target, scorer.score(profile, target)))
                .filter(scored -> scored.score >= CaseScorer.MIN_SCORE)
                .sorted(Comparator.comparingInt((Scored s) -> s.score).reversed()
                        .thenComparing(s -> s.target.getId()))
                .limit(TOP_N)
                .map(scored -> SimilarCaseResponse.from(scored.target,
                        scorer.matchedTags(profile, scored.target)))
                .toList();

        return SimilarCasesResponse.of(matched);
    }

    private record Scored(ReunionCase target, int score) {
    }

}
