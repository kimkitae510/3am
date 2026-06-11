package com.threeam.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.review.dto.ReviewCommentRequest;
import com.threeam.review.dto.ReviewStatusResponse;
import com.threeam.review.dto.ReviewSubmitRequest;
import com.threeam.review.dto.ReviewSubmitResponse;
import com.threeam.review.entity.AssessmentReview;
import com.threeam.review.repository.AssessmentReviewRepository;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import com.threeam.usage.UsageProperties;
import com.threeam.usage.WelcomeGiftService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long STORY_ID = 10L;
    private static final Long ASSESSMENT_ID = 77L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private AssessmentReviewRepository reviewRepository;

    @Mock
    private WelcomeGiftService welcomeGiftService;

    @Mock
    private UsageProperties usageProperties;

    @InjectMocks
    private ReviewService reviewService;

    private void givenOwnedStoryWithAssessment() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(mock(Story.class)));
        Assessment assessment = mock(Assessment.class);
        given(assessment.getId()).willReturn(ASSESSMENT_ID);
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment));
    }

    private AssessmentReview review(int score) {
        return AssessmentReview.builder()
                .userId(USER_ID).assessmentId(ASSESSMENT_ID).score(score).build();
    }

    @Test
    @DisplayName("첫 점수 제출은 새 행으로 저장하고 보상은 없다")
    void firstScoreSavesWithoutReward() {
        givenOwnedStoryWithAssessment();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.empty());

        reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(5));

        verify(reviewRepository).saveAndFlush(any(AssessmentReview.class));
        verifyNoInteractions(welcomeGiftService);
    }

    @Test
    @DisplayName("점수 재제출은 기존 행을 고친다(업서트)")
    void scoreResubmitUpdatesExisting() {
        givenOwnedStoryWithAssessment();
        AssessmentReview existing = review(2);
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(existing));

        reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(4));

        assertThat(existing.getScore()).isEqualTo(4);
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("점수 범위(1~5)를 벗어나면 거부한다")
    void scoreOutOfRangeRejected() {
        assertThatThrownBy(() ->
                reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() ->
                reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(6)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(storyRepository, reviewRepository, welcomeGiftService);
    }

    @Test
    @DisplayName("남의 사연에는 점수를 남길 수 없다")
    void foreignStoryRejected() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("진단이 없는 사연이면 평가 대상이 없다고 알린다")
    void noAssessmentRejected() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(mock(Story.class)));
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                reviewService.submitScore(USER_ID, STORY_ID, new ReviewSubmitRequest(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_TARGET_NOT_FOUND);
    }

    @Test
    @DisplayName("첫 후기 완성 시에만 보상을 지급한다")
    void firstCompletedCommentGrantsReward() {
        givenOwnedStoryWithAssessment();
        AssessmentReview existing = review(5);
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(existing));
        given(reviewRepository.existsByUserIdAndCommentIsNotNull(USER_ID)).willReturn(false);
        given(usageProperties.getReviewGiftChat()).willReturn(2);

        ReviewSubmitResponse response =
                reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest(" 유형이 소름 "));

        assertThat(response.chatBonus()).isEqualTo(2);
        assertThat(existing.getComment()).isEqualTo("유형이 소름");
        verify(welcomeGiftService).grantReviewGift(USER_ID);
    }

    @Test
    @DisplayName("이미 보상을 받은 유저의 후기는 저장만 하고 지급하지 않는다")
    void laterCommentSkipsReward() {
        givenOwnedStoryWithAssessment();
        AssessmentReview existing = review(4);
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(existing));
        given(reviewRepository.existsByUserIdAndCommentIsNotNull(USER_ID)).willReturn(true);

        ReviewSubmitResponse response =
                reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest("수정된 후기"));

        assertThat(response.chatBonus()).isZero();
        assertThat(existing.getComment()).isEqualTo("수정된 후기");
        verifyNoInteractions(welcomeGiftService);
    }

    @Test
    @DisplayName("후기는 점수를 남긴 뒤에만 붙는다")
    void commentRequiresScore() {
        givenOwnedStoryWithAssessment();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest("내용")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_TARGET_NOT_FOUND);
        verifyNoInteractions(welcomeGiftService);
    }

    @Test
    @DisplayName("빈 후기는 거부한다")
    void blankCommentRejected() {
        assertThatThrownBy(() ->
                reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest("  ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(storyRepository, reviewRepository);
    }

    @Test
    @DisplayName("상태 조회는 점수, 후기, 보상 가능 여부를 함께 내린다")
    void statusCarriesScoreCommentAndReward() {
        givenOwnedStoryWithAssessment();
        AssessmentReview existing = review(4);
        existing.updateComment("좋았어요");
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(existing));
        given(reviewRepository.existsByUserIdAndCommentIsNotNull(USER_ID)).willReturn(true);

        assertThat(reviewService.status(USER_ID, STORY_ID))
                .isEqualTo(new ReviewStatusResponse(true, 4, "좋았어요", false));
    }
}
