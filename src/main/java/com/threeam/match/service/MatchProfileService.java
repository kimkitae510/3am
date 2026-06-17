package com.threeam.match.service;

import com.threeam.assessment.dto.ReunionDiagnosis.MatchProfileItem;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.entity.SubReasons;
import com.threeam.match.repository.StoryMatchProfileRepository;
import com.threeam.story.entity.StoryIntake;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.service.StoryIntakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchProfileService {

    private final StoryMatchProfileRepository profileRepository;
    private final StoryIntakeRepository intakeRepository;

    // 분석 저장 트랜잭션 안에서 불린다(REQUIRED). 프로필은 분석의 부산물이라
    // 분석이 롤백되면 함께 되돌아가는 편이 맞다 — 있지도 않은 분석이 뽑은 분류가 남으면 안 된다.
    // 덮어쓰지 않고 분석마다 한 행씩 쌓는다 — 분석은 하루 1회 쿼터라 양이 문제될 일이 없고,
    // 과거 분석 시점의 상황을 남겨야 나중에 되짚을 수 있다(assessments와 같은 문법).
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(Long storyId, MatchProfileItem item) {
        if (item == null) {
            return;
        }
        StoryIntake intake = intakeRepository.findByStoryId(storyId).orElse(null);
        StoryMatchProfile fresh = StoryMatchProfile.builder()
                .storyId(storyId)
                .reason(item.reason())
                .subReasons(SubReasons.join(item.subReasons()))
                // 유저가 폼에서 직접 고른 값은 추출값을 이긴다. 안 변하는 사실이라 대화가
                // 쌓여도 뒤집힐 이유가 없고, 산문에서 추론하다 틀리던 자리(특히 나떠밀림)를
                // 유저의 답으로 못 박는다.
                .dumper(preferIntake(dumperOf(intake), item.dumper()))
                .fault(item.fault())
                // 반대로 연락 상태와 상대의 새 사람은 시간이 지나면 바뀐다. 폼은 첫 시점의
                // 스냅샷이라 최신 대화에서 뽑힌 값이 있으면 그쪽이 이기고, 폼은 빈 자리만 메운다.
                .contactState(preferExtracted(item.contactState(), contactStateOf(intake)))
                .monthsSinceBreakup(preferIntake(
                        StoryIntakeService.elapsedMonths(intake), item.monthsSinceBreakup()))
                .datingMonths(preferIntake(
                        intake == null ? null : intake.getDatingMonths(), item.datingMonths()))
                .ageGroup(preferIntake(StoryIntakeService.currentAgeGroup(intake), item.ageGroup()))
                .gender(preferIntake(genderOf(intake), item.gender()))
                .repeatBreakup(preferIntake(repeatOf(intake), item.repeatBreakup()))
                .partnerHasNew(preferExtracted(item.partnerHasNew(), partnerHasNewOf(intake)))
                .build();

        // 이번 분석이 못 뽑은 항목은 직전 스냅샷에서 이어받아 저장한다.
        profileRepository.findFirstByStoryIdOrderByIdDesc(storyId)
                .ifPresent(fresh::backfillFrom);
        profileRepository.save(fresh);
    }

    private static <T> T preferIntake(T fromIntake, T extracted) {
        return fromIntake != null ? fromIntake : extracted;
    }

    private static <T> T preferExtracted(T extracted, T fromIntake) {
        return extracted != null ? extracted : fromIntake;
    }

    private static String dumperOf(StoryIntake intake) {
        return intake == null || intake.getInitiator() == null ? null : intake.getInitiator().dumper();
    }

    private static String contactStateOf(StoryIntake intake) {
        return intake == null || intake.getContactMode() == null
                ? null : intake.getContactMode().contactState();
    }

    private static String genderOf(StoryIntake intake) {
        return intake == null || intake.getUserGender() == null
                ? null : intake.getUserGender().caseVocabulary();
    }

    private static Boolean repeatOf(StoryIntake intake) {
        return intake == null || intake.getPriorReunion() == null
                ? null : intake.getPriorReunion().repeated();
    }

    private static Boolean partnerHasNewOf(StoryIntake intake) {
        return intake == null || intake.getPartnerHasNew() == null
                ? null : intake.getPartnerHasNew().toFlag();
    }
}
