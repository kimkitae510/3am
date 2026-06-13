package com.threeam.match.service;

import com.threeam.assessment.dto.ReunionDiagnosis.MatchProfileItem;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.entity.SubReasons;
import com.threeam.match.repository.StoryMatchProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchProfileService {

    private final StoryMatchProfileRepository profileRepository;

    // 분석 저장 트랜잭션 안에서 불린다(REQUIRED). 프로필은 분석의 부산물이라
    // 분석이 롤백되면 함께 되돌아가는 편이 맞다 — 있지도 않은 분석이 뽑은 분류가 남으면 안 된다.
    // 덮어쓰지 않고 분석마다 한 행씩 쌓는다 — 분석은 하루 1회 쿼터라 양이 문제될 일이 없고,
    // 과거 분석 시점의 상황을 남겨야 나중에 되짚을 수 있다(assessments와 같은 문법).
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(Long storyId, MatchProfileItem item) {
        if (item == null) {
            return;
        }
        StoryMatchProfile fresh = StoryMatchProfile.builder()
                .storyId(storyId)
                .reason(item.reason())
                .subReasons(SubReasons.join(item.subReasons()))
                .dumper(item.dumper())
                .fault(item.fault())
                .contactState(item.contactState())
                .monthsSinceBreakup(item.monthsSinceBreakup())
                .datingMonths(item.datingMonths())
                .ageGroup(item.ageGroup())
                .gender(item.gender())
                .repeatBreakup(item.repeatBreakup())
                .partnerHasNew(item.partnerHasNew())
                .build();

        // 이번 분석이 못 뽑은 항목은 직전 스냅샷에서 이어받아 저장한다.
        profileRepository.findFirstByStoryIdOrderByIdDesc(storyId)
                .ifPresent(fresh::backfillFrom);
        profileRepository.save(fresh);
    }
}
