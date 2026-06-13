package com.threeam.assessment.service;

import com.threeam.assessment.dto.ShareCreateResponse;
import com.threeam.assessment.dto.SharedAssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentShare;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.assessment.repository.AssessmentShareRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.repository.StoryRepository;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 분석 결과 공유. 링크는 분석 1건에 1개(스냅샷) — 재분석해도 이미 보낸 링크의 내용은
// 그대로고(받은 쪽에서 내용이 몰래 바뀌지 않게), 같은 분석을 다시 공유하면 토큰을 재사용한다.
@Service
@RequiredArgsConstructor
public class AssessmentShareService {

    // 24바이트(192비트) 랜덤 → base64url 32자. 무인증 공개 조회라 토큰이 곧 열쇠다.
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StoryRepository storyRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentShareRepository shareRepository;

    @Transactional
    public ShareCreateResponse create(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Assessment latest = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_AVAILABLE));
        // 잠금 판정(사귀는 중, 재회 성공)은 확률 화면이 아니라 공유 대상이 아니다
        if (latest.getProbability() == null) {
            throw new BusinessException(ErrorCode.SHARE_NOT_AVAILABLE);
        }
        AssessmentShare share = shareRepository.findByAssessmentId(latest.getId())
                .orElseGet(() -> shareRepository.save(
                        AssessmentShare.of(latest.getId(), storyId, userId, newToken())));
        return new ShareCreateResponse(share.getToken());
    }

    // 요인 목록(@ElementCollection, LAZY)을 매핑에서 읽으므로 트랜잭션 안이어야 한다.
    @Transactional(readOnly = true)
    public SharedAssessmentResponse getShared(String token) {
        AssessmentShare share = shareRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        // 방을 지우면 링크도 죽는다 — 공유는 방의 파생물이다
        storyRepository.findByIdAndDeletedAtIsNull(share.getStoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        Assessment assessment = assessmentRepository.findById(share.getAssessmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        return SharedAssessmentResponse.from(assessment);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
