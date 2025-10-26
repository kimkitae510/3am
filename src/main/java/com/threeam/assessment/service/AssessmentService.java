package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentRequest;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssessmentService {

    private final StoryRepository storyRepository;
    private final AssessmentRepository assessmentRepository;
    private final ReunionScorer scorer;

    @Transactional
    public AssessmentResponse assess(Long userId, Long storyId, AssessmentRequest request) {
        Story story = findOwned(storyId, userId);

        ReunionScore result = scorer.score(request);

        Assessment saved = assessmentRepository.save(Assessment.builder()
                .storyId(story.getId())
                .verdict(result.verdict())
                .probability(result.probability())
                .myBreakupType(result.breakupType())
                .partnerType(result.partnerType())
                .reason(result.reason())
                // 점수 근거가 된 신호값을 스냅샷으로 함께 남긴다.
                .whoEnded(request.getWhoEnded())
                .contactStatus(request.getContactStatus())
                .breakupReason(request.getBreakupReason())
                .partnerNewPerson(request.isPartnerNewPerson())
                .relationshipMonths(request.getRelationshipMonths())
                .pastReunionFailed(request.isPastReunionFailed())
                .daysSinceBreakup(request.getDaysSinceBreakup())
                .build());

        return AssessmentResponse.from(saved);
    }

    public List<AssessmentResponse> getHistory(Long userId, Long storyId) {
        findOwned(storyId, userId);
        return assessmentRepository.findByStoryIdOrderByCreatedAtDesc(storyId).stream()
                .map(AssessmentResponse::from)
                .toList();
    }

    private Story findOwned(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }
}
