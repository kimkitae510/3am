package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.threeam.assessment.dto.ReunionDiagnosis.MatchProfileItem;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.repository.StoryMatchProfileRepository;
import com.threeam.story.entity.BreakupInitiator;
import com.threeam.story.entity.ContactMode;
import com.threeam.story.entity.IntakeGender;
import com.threeam.story.entity.PartnerNewRelation;
import com.threeam.story.entity.PriorReunion;
import com.threeam.story.entity.StoryIntake;
import com.threeam.story.repository.StoryIntakeRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 폼 값과 추출값이 부딪힐 때 누가 이기는지를 못 박는다. 안 변하는 사실은 폼이,
// 시간이 지나면 바뀌는 값은 최신 추출이 이긴다.
@ExtendWith(MockitoExtension.class)
class MatchProfileServiceTest {

    private static final Long STORY_ID = 10L;

    @Mock
    private StoryMatchProfileRepository profileRepository;

    @Mock
    private StoryIntakeRepository intakeRepository;

    @InjectMocks
    private MatchProfileService service;

    @Test
    @DisplayName("안 변하는 사실은 폼이 추출값을 이긴다")
    void intakeWinsForStableFacts() {
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.of(intake()));
        given(profileRepository.findFirstByStoryIdOrderByIdDesc(STORY_ID)).willReturn(Optional.empty());

        service.append(STORY_ID, extracted("나", "연락중", true));

        StoryMatchProfile saved = captureSaved();
        // 폼에서 나떠밀림을 고른 유저다 — 대화에서 "내가 헤어지자 했다"로 읽혀도 폼이 이긴다
        assertThat(saved.getDumper()).isEqualTo("나떠밀림");
        assertThat(saved.getGender()).isEqualTo("여");
        assertThat(saved.getAgeGroup()).isEqualTo("20대 후반");
        assertThat(saved.getDatingMonths()).isEqualTo(26);
        assertThat(saved.getRepeatBreakup()).isFalse();
        // 경과는 폼 입력일 이후 흐른 날까지 더해 계산된다
        assertThat(saved.getMonthsSinceBreakup()).isEqualTo(2);
    }

    @Test
    @DisplayName("연락 상태와 상대의 새 사람은 최신 추출이 폼을 이긴다")
    void extractedWinsForVolatileFacts() {
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.of(intake()));
        given(profileRepository.findFirstByStoryIdOrderByIdDesc(STORY_ID)).willReturn(Optional.empty());

        service.append(STORY_ID, extracted("나", "차단", true));

        StoryMatchProfile saved = captureSaved();
        // 폼에는 "상대가 먼저 연락해 온다"였지만 그 뒤 차단됐다
        assertThat(saved.getContactState()).isEqualTo("차단");
        assertThat(saved.getPartnerHasNew()).isTrue();
    }

    @Test
    @DisplayName("추출이 비어 있으면 폼이 그 자리를 메운다")
    void intakeFillsBlanksLeftByExtraction() {
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.of(intake()));
        given(profileRepository.findFirstByStoryIdOrderByIdDesc(STORY_ID)).willReturn(Optional.empty());

        service.append(STORY_ID, extracted(null, null, null));

        StoryMatchProfile saved = captureSaved();
        assertThat(saved.getContactState()).isEqualTo("상대가연락");
        // 폼에서 "모름"을 고른 값은 flag가 null이라 프로필에도 안 실린다
        assertThat(saved.getPartnerHasNew()).isNull();
    }

    @Test
    @DisplayName("폼이 없는 사연은 지금까지처럼 추출값만 쓴다")
    void fallsBackToExtractionWithoutIntake() {
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(profileRepository.findFirstByStoryIdOrderByIdDesc(STORY_ID)).willReturn(Optional.empty());

        service.append(STORY_ID, extracted("나", "연락중", true));

        StoryMatchProfile saved = captureSaved();
        assertThat(saved.getDumper()).isEqualTo("나");
        assertThat(saved.getContactState()).isEqualTo("연락중");
    }

    private StoryMatchProfile captureSaved() {
        ArgumentCaptor<StoryMatchProfile> captor = ArgumentCaptor.forClass(StoryMatchProfile.class);
        org.mockito.BDDMockito.then(profileRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private static MatchProfileItem extracted(String dumper, String contactState, Boolean partnerHasNew) {
        return new MatchProfileItem("성격차이", List.of("갈등회피"), dumper, "양쪽",
                contactState, 9, 3, "30대 초반", "남", false, partnerHasNew);
    }

    // 40일 전에 "이별한 지 22일"로 적어냈다 → 지금은 62일째(2개월)
    private static StoryIntake intake() {
        StoryIntake intake = StoryIntake.builder()
                .storyId(STORY_ID)
                .userAge(28)
                .userGender(IntakeGender.FEMALE)
                .datingMonths(26)
                .daysSinceBreakup(22)
                .initiator(BreakupInitiator.PUSHED)
                .contactMode(ContactMode.PARTNER_REACHES)
                .priorReunion(PriorReunion.NONE)
                .partnerHasNew(PartnerNewRelation.UNKNOWN)
                .build();
        try {
            Field field = StoryIntake.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(intake, LocalDateTime.now().minusDays(40));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return intake;
    }
}
