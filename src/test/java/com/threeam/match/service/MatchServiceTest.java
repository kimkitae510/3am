package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.match.dto.SimilarCasesResponse;
import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.repository.ReunionCaseRepository;
import com.threeam.match.repository.StoryMatchProfileRepository;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private StoryMatchProfileRepository profileRepository;

    @Mock
    private ReunionCaseRepository caseRepository;

    @org.mockito.Spy
    private CaseScorer scorer = new CaseScorer();

    @InjectMocks
    private MatchService matchService;

    private static final Long USER_ID = 1L;
    private static final Long STORY_ID = 10L;

    private void ownsStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(Story.builder().userId(USER_ID).title("사연").build()));
    }

    private ReunionCase reunionCase(long id, String reason, String subReasons) {
        ReunionCase target = BeanUtils.instantiateClass(ReunionCase.class);
        ReflectionTestUtils.setField(target, "id", id);
        ReflectionTestUtils.setField(target, "reason", reason);
        ReflectionTestUtils.setField(target, "subReasons", subReasons);
        ReflectionTestUtils.setField(target, "story", "사례 본문 " + id);
        return target;
    }

    @Test
    @DisplayName("남의 사연이면 STORY_NOT_FOUND")
    void storyNotFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> matchService.findSimilar(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("프로필이 없으면 NO_PROFILE - 사례 조회조차 하지 않는다")
    void noProfile() {
        ownsStory();
        given(profileRepository.findById(STORY_ID)).willReturn(Optional.empty());

        SimilarCasesResponse response = matchService.findSimilar(USER_ID, STORY_ID);

        assertThat(response.cases()).isEmpty();
        assertThat(response.emptyReason()).isEqualTo("NO_PROFILE");
        org.mockito.Mockito.verifyNoInteractions(caseRepository);
    }

    @Test
    @DisplayName("사유 축이 비면 NO_PROFILE - 나이, 기간만으로는 매칭하지 않는다")
    void profileWithoutReasonIsNotMatchable() {
        ownsStory();
        given(profileRepository.findById(STORY_ID)).willReturn(Optional.of(
                StoryMatchProfile.builder().storyId(STORY_ID).ageGroup("20대 후반").build()));

        SimilarCasesResponse response = matchService.findSimilar(USER_ID, STORY_ID);

        assertThat(response.emptyReason()).isEqualTo("NO_PROFILE");
    }

    @Test
    @DisplayName("임계 미달만 있으면 NO_MATCH")
    void belowThreshold() {
        ownsStory();
        given(profileRepository.findById(STORY_ID)).willReturn(Optional.of(
                StoryMatchProfile.builder().storyId(STORY_ID).reason("장거리")
                        .subReasons("거리지침").build()));
        given(caseRepository.findAll()).willReturn(List.of(reunionCase(1L, "외도", "상대가바람")));

        SimilarCasesResponse response = matchService.findSimilar(USER_ID, STORY_ID);

        assertThat(response.cases()).isEmpty();
        assertThat(response.emptyReason()).isEqualTo("NO_MATCH");
    }

    @Test
    @DisplayName("점수 높은 순으로 2건만 준다 - 데이터가 적어 세 번째부터는 안 닮는다")
    void topTwoByScore() {
        ownsStory();
        given(profileRepository.findById(STORY_ID)).willReturn(Optional.of(
                StoryMatchProfile.builder().storyId(STORY_ID).reason("본인과실")
                        .subReasons("질투의심,무심소홀").build()));
        given(caseRepository.findAll()).willReturn(List.of(
                reunionCase(1L, "본인과실", "무심소홀,질투의심"),  // 엇갈림 겹침
                reunionCase(2L, "본인과실", "질투의심,무심소홀"),  // 주와 부가 모두 일치(최고점)
                reunionCase(3L, "본인과실", "질투의심")));         // 주만 일치

        SimilarCasesResponse response = matchService.findSimilar(USER_ID, STORY_ID);

        assertThat(response.cases()).hasSize(2);
        assertThat(response.cases().get(0).id()).isEqualTo(2L);
        assertThat(response.cases().get(1).id()).isEqualTo(3L);
        assertThat(response.emptyReason()).isNull();
    }

    @Test
    @DisplayName("동점이면 id 순으로 갈라 새로고침해도 같은 사례를 보여준다")
    void tieBrokenDeterministically() {
        ownsStory();
        given(profileRepository.findById(STORY_ID)).willReturn(Optional.of(
                StoryMatchProfile.builder().storyId(STORY_ID).reason("잦은싸움")
                        .subReasons("사소한반복").build()));
        given(caseRepository.findAll()).willReturn(List.of(
                reunionCase(7L, "잦은싸움", "사소한반복"),
                reunionCase(3L, "잦은싸움", "사소한반복"),
                reunionCase(5L, "잦은싸움", "사소한반복")));

        SimilarCasesResponse first = matchService.findSimilar(USER_ID, STORY_ID);
        SimilarCasesResponse second = matchService.findSimilar(USER_ID, STORY_ID);

        assertThat(first.cases()).extracting("id").containsExactly(3L, 5L);
        assertThat(second.cases()).extracting("id").containsExactly(3L, 5L);
    }
}
