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
// 점수는 업서트(마음이 바뀌면 다시 누른다), 후기도 언제든 고칠 수 있다.
// 보상은 후기까지 완성했을 때 유저당 1회 — 점수 원탭만으로 나가면 탭 5번이 대화 2회가 된다.
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
        boolean rewardAvailable = !reviewRepository.existsByUserIdAndCommentIsNotNull(userId);
        if (assessmentId == null) {
            return new ReviewStatusResponse(false, null, null, rewardAvailable);
        }
        return reviewRepository.findByAssessmentId(assessmentId)
                .map(r -> new ReviewStatusResponse(true, r.getScore(), r.getComment(), rewardAvailable))
                .orElseGet(() -> new ReviewStatusResponse(false, null, null, rewardAvailable));
    }

    @Transactional
    public void submitScore(Long userId, Long storyId, ReviewSubmitRequest request) {
        if (request.score() < 1 || request.score() > 5) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long assessmentId = requireLatestAssessmentId(userId, storyId);
        AssessmentReview existing = reviewRepository.findByAssessmentId(assessmentId).orElse(null);
        if (existing != null) {
            existing.updateScore(request.score());
            return;
        }
        try {
            reviewRepository.saveAndFlush(AssessmentReview.builder()
                    .userId(userId)
                    .assessmentId(assessmentId)
                    .score(request.score())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시 이중 탭의 경합 — 진 쪽이다. 승자가 방금 같은 점수를 넣었을 확률이 높고,
            // flush 실패로 트랜잭션이 롤백 전용이라 여기서 더 쓸 수도 없다. 그냥 성공으로
            // 돌려보낸다 — 다르게 고치고 싶으면 다시 누르는 순간 업서트 경로로 반영된다.
        }
    }

    // 후기(텍스트). 점수를 남긴 진단에만 붙는다. 보상은 "후기까지 완성한 첫 번째" 한 번뿐 —
    // 판별은 갱신 전에 해야 이번에 붙이는 후기가 자기 자신을 이력으로 잡지 않는다.
    @Transactional
    public ReviewSubmitResponse addComment(Long userId, Long storyId, ReviewCommentRequest request) {
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (comment.isEmpty() || comment.length() > COMMENT_MAX) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long assessmentId = requireLatestAssessmentId(userId, storyId);
        AssessmentReview review = reviewRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TARGET_NOT_FOUND));
        boolean firstCompleted = !reviewRepository.existsByUserIdAndCommentIsNotNull(userId);
        review.updateComment(comment);
        if (!firstCompleted) {
            return new ReviewSubmitResponse(0);
        }
        welcomeGiftService.grantReviewGift(userId);
        return new ReviewSubmitResponse(usageProperties.getReviewGiftChat());
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
