package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.dto.AssessmentRequest;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ReunionScorer scorer;

    @InjectMocks
    private AssessmentService assessmentService;

    @Test
    @DisplayName("진단 - 소유한 사연이면 점수 계산 후 저장하고 결과를 반환한다")
    void assess_success() {
        Story story = story(1L);
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.of(story));
        given(scorer.score(any(AssessmentRequest.class))).willReturn(new ReunionScore(
                ReunionVerdict.POSSIBLE, 23, BreakupType.CLINGER, PartnerType.COLD, "근거"));
        given(assessmentRepository.save(any(Assessment.class))).willAnswer(inv -> inv.getArgument(0));

        AssessmentResponse response = assessmentService.assess(1L, 10L, new AssessmentRequest());

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(response.getProbability()).isEqualTo(23);
        assertThat(response.getMyBreakupType()).isEqualTo("매달림형");
        assertThat(response.getPartnerType()).isEqualTo("냉담형");
        verify(assessmentRepository).save(any(Assessment.class));
    }

    @Test
    @DisplayName("진단 - 없거나 남의 사연이면 STORY_NOT_FOUND, 저장하지 않는다")
    void assess_storyNotFound() {
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L, new AssessmentRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(assessmentRepository, never()).save(any(Assessment.class));
    }

    @Test
    @DisplayName("히스토리 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void getHistory_storyNotFound() {
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> assessmentService.getHistory(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    private Story story(Long id) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "id", id);
        return story;
    }
}
