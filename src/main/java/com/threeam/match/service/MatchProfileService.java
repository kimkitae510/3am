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

    // 진단 저장 트랜잭션 안에서 불린다(REQUIRED). 프로필은 진단의 부산물이라
    // 진단이 롤백되면 함께 되돌아가는 편이 맞다 — 있지도 않은 진단이 뽑은 분류가 남으면 안 된다.
    @Transactional(propagation = Propagation.REQUIRED)
    public void upsert(Long storyId, MatchProfileItem item) {
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
                .build();

        // 있으면 덮어쓰되 이번에 안 드러난 항목은 지키고(merge), 없으면 새로 만든다.
        profileRepository.findById(storyId)
                .ifPresentOrElse(existing -> existing.merge(fresh),
                        () -> profileRepository.save(fresh));
    }
}
