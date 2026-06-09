package com.threeam.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    @Test
    @DisplayName("점수 제출 성공 시 저장하고 보상을 지급한다")
    void submitGrantsReward() {
        givenOwnedStoryWithAssessment();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.empty());
        given(usageProperties.getReviewGiftChat()).willReturn(2);

        ReviewSubmitResponse response =
                reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(5));

        assertThat(response.chatBonus()).isEqualTo(2);
        verify(reviewRepository).saveAndFlush(any(AssessmentReview.class));
        verify(welcomeGiftService).grantReviewGift(USER_ID);
    }

    @Test
    @DisplayName("이미 평가한 진단이면 거부하고 보상도 지급하지 않는다")
    void submitRejectsDuplicate() {
        givenOwnedStoryWithAssessment();
        AssessmentReview existing = AssessmentReview.builder()
                .userId(USER_ID).assessmentId(ASSESSMENT_ID).score(4).build();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(existing));

        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_ALREADY_SUBMITTED);
        verifyNoInteractions(welcomeGiftService);
    }

    @Test
    @DisplayName("동시 이중 제출로 유니크 제약에 걸려도 중복 응답으로 정리되고 보상은 없다")
    void submitRejectsConcurrentDuplicate() {
        givenOwnedStoryWithAssessment();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.empty());
        given(reviewRepository.saveAndFlush(any(AssessmentReview.class)))
                .willThrow(new DataIntegrityViolationException("uk_review_assessment"));

        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(4)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_ALREADY_SUBMITTED);
        verifyNoInteractions(welcomeGiftService);
    }

    @Test
    @DisplayName("점수 범위(1~5)를 벗어나면 거부한다")
    void submitRejectsOutOfRangeScore() {
        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(6)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(storyRepository, reviewRepository, welcomeGiftService);
    }

    @Test
    @DisplayName("남의 사연에는 평가할 수 없다")
    void submitRejectsForeignStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("진단이 없는 사연이면 평가 대상이 없다고 알린다")
    void submitRejectsWhenNoAssessment() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(mock(Story.class)));
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        Assertions.assertThatThrownBy(() ->
                        reviewService.submit(USER_ID, STORY_ID, new ReviewSubmitRequest(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_TARGET_NOT_FOUND);
    }

    @Test
    @DisplayName("텍스트는 점수를 남긴 뒤에만 이어서 남길 수 있다")
    void commentRequiresExistingReview() {
        givenOwnedStoryWithAssessment();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.empty());

        Assertions.assertThatThrownBy(() ->
                        reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest("내용")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_TARGET_NOT_FOUND);
    }

    @Test
    @DisplayName("텍스트를 남기면 기존 평가에 붙는다")
    void commentAttachesToReview() {
        givenOwnedStoryWithAssessment();
        AssessmentReview review = AssessmentReview.builder()
                .userId(USER_ID).assessmentId(ASSESSMENT_ID).score(5).build();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(review));

        reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest(" 유형 분석이 소름 "));

        assertThat(review.getComment()).isEqualTo("유형 분석이 소름");
    }

    @Test
    @DisplayName("빈 텍스트는 거부한다")
    void commentRejectsBlank() {
        Assertions.assertThatThrownBy(() ->
                        reviewService.addComment(USER_ID, STORY_ID, new ReviewCommentRequest("  ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(storyRepository, reviewRepository);
    }

    @Test
    @DisplayName("평가 여부 조회: 진단이 없으면 미평가, 평가가 있으면 점수와 함께 내린다")
    void statusReflectsReview() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(mock(Story.class)));
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());
        assertThat(reviewService.status(USER_ID, STORY_ID))
                .isEqualTo(new ReviewStatusResponse(false, null));

        givenOwnedStoryWithAssessment();
        AssessmentReview review = AssessmentReview.builder()
                .userId(USER_ID).assessmentId(ASSESSMENT_ID).score(4).build();
        given(reviewRepository.findByAssessmentId(ASSESSMENT_ID)).willReturn(Optional.of(review));
        assertThat(reviewService.status(USER_ID, STORY_ID))
                .isEqualTo(new ReviewStatusResponse(true, 4));
    }
}
