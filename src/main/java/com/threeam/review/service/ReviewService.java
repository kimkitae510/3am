package com.threeam.review.service;

import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.review.dto.ReviewCommentRequest;
import com.threeam.review.dto.ReviewStatusResponse;
import com.threeam.review.dto.ReviewSubmitRequest;
import com.threeam.review.dto.ReviewSubmitResponse;
import com.threeam.review.entity.AssessmentReview;
import com.threeam.review.repository.AssessmentReviewRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.usage.UsageProperties;
import com.threeam.usage.WelcomeGiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 진단 평가. 평가 대상은 항상 "그 사연의 최신 진단"이다 — 화면이 보여주는 결과가 최신
// 진단이므로, 진단 id를 클라이언트에 노출하지 않고 storyId만 받아 서버가 대상을 정한다.
// 점수와 텍스트를 두 단계로 나눈 이유: 텍스트를 쓰다 이탈해도 점수(핵심 데이터)는 남는다.
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int COMMENT_MAX = 1000;

    private final StoryRepository storyRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentReviewRepository reviewRepository;
    private final WelcomeGiftService welcomeGiftService;
    private final UsageProperties usageProperties;

    @Transactional(readOnly = true)
    public ReviewStatusResponse status(Long userId, Long storyId) {
        Long assessmentId = latestAssessmentId(userId, storyId);
        if (assessmentId == null) {
            return new ReviewStatusResponse(false, null);
        }
        return reviewRepository.findByAssessmentId(assessmentId)
                .map(r -> new ReviewStatusResponse(true, r.getScore()))
                .orElseGet(() -> new ReviewStatusResponse(false, null));
    }

    @Transactional
    public ReviewSubmitResponse submit(Long userId, Long storyId, ReviewSubmitRequest request) {
        if (request.score() < 1 || request.score() > 5) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long assessmentId = requireLatestAssessmentId(userId, storyId);
        if (reviewRepository.findByAssessmentId(assessmentId).isPresent()) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_SUBMITTED);
        }
        try {
            // 동시 이중 제출은 사전 조회를 둘 다 통과할 수 있다 — 유니크 제약이 최종 방어선이고,
            // 여기서 flush해 지급 전에 걸러낸다(보상이 나간 뒤 롤백되는 것보다 명확하다).
            reviewRepository.saveAndFlush(AssessmentReview.builder()
                    .userId(userId)
                    .assessmentId(assessmentId)
                    .score(request.score())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_SUBMITTED);
        }
        welcomeGiftService.grantReviewGift(userId);
        return new ReviewSubmitResponse(usageProperties.getReviewGiftChat());
    }

    @Transactional
    public void addComment(Long userId, Long storyId, ReviewCommentRequest request) {
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (comment.isEmpty() || comment.length() > COMMENT_MAX) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long assessmentId = requireLatestAssessmentId(userId, storyId);
        AssessmentReview review = reviewRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TARGET_NOT_FOUND));
        review.updateComment(comment);
    }

    // 사연 소유 검증을 겸한다 — 남의 storyId로는 최신 진단 id 자체를 알 수 없다.
    private Long latestAssessmentId(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        return assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .map(a -> a.getId())
                .orElse(null);
    }

    private Long requireLatestAssessmentId(Long userId, Long storyId) {
        Long assessmentId = latestAssessmentId(userId, storyId);
        if (assessmentId == null) {
            throw new BusinessException(ErrorCode.REVIEW_TARGET_NOT_FOUND);
        }
        return assessmentId;
    }
}
