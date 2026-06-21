package com.threeam.match.service;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.match.entity.MatchPick;
import com.threeam.match.entity.MatchSelection;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.match.repository.MatchSelectionRepository;
import com.threeam.match.repository.StoryMatchProfileRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 유료 매칭의 DB 접촉면. LLM 호출은 트랜잭션 밖에서 일어나야 하므로(왕복이 수십 초다)
// 재료를 모으는 짧은 트랜잭션과 결과를 남기는 짧은 트랜잭션으로 갈라 둔다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaidMatchTxService {

    private final StoryRepository storyRepository;
    private final AssessmentRepository assessmentRepository;
    private final StoryMatchProfileRepository profileRepository;
    private final MatchSelectionRepository selectionRepository;

    // assessmentId: 결과를 묶어둘 진단. probability: 대역 판단용. digest: LLM에 넘길 상황 요지.
    public record Material(Long assessmentId, Integer probability, StoryMatchProfile profile,
                           String digest) {
    }

    public record SavedSelection(MatchPick.Picks picks, StoryMatchProfile profile) {
    }

    @Transactional(readOnly = true)
    public Optional<SavedSelection> findSaved(Long userId, Long storyId) {
        requireStory(userId, storyId);
        return assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .flatMap(latest -> selectionRepository.findByAssessmentId(latest.getId()))
                .map(selection -> new SavedSelection(selection.getPicks(), latestProfile(storyId)));
    }

    @Transactional(readOnly = true)
    public Material loadMaterial(Long userId, Long storyId) {
        requireStory(userId, storyId);
        Assessment latest = assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCH_NOT_AVAILABLE));
        StoryMatchProfile profile = latestProfile(storyId);
        return new Material(latest.getId(), latest.getProbability(), profile, digestOf(profile));
    }

    // 이미 있으면 덮어쓰지 않는다. assessment_id UNIQUE와 함께, 같은 진단에 두 벌이 생기는 걸
    // 두 겹으로 막는다 — 동시 요청이 락을 스쳐 지나가도 먼저 저장된 쪽이 유저의 결과다.
    //
    // 실제로 넣었을 때만 true다. 이 값이 곧 "차감해도 되는가"다 — 락 TTL(100초)보다 LLM이
    // 오래 끌면 두 번째 요청이 만료된 락을 뺏어 들어올 수 있고, 그때 늦은 쪽은 저장에 실패하는데도
    // 차감하면 결과 하나에 두 번 나간다. 유료라 이 구멍은 못 남긴다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean save(Long assessmentId, Long storyId, Long userId, MatchPick.Picks picks) {
        if (selectionRepository.findByAssessmentId(assessmentId).isPresent()) {
            return false;
        }
        try {
            selectionRepository.save(MatchSelection.of(assessmentId, storyId, userId, picks));
            return true;
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 충돌 = 그 사이에 다른 요청이 저장했다. 먼저 들어간 것을 그대로 둔다.
            log.warn("매칭 선택 중복 저장 시도 assessmentId={}", assessmentId);
            return false;
        }
    }

    private void requireStory(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }

    private StoryMatchProfile latestProfile(Long storyId) {
        return profileRepository.findFirstByStoryIdOrderByIdDesc(storyId).orElse(null);
    }

    // LLM이 후보를 고를 때 쓰는 상황 요지. 대화 원문은 따로 넘어가므로 여기엔 분류값만 담는다 —
    // 대화가 길면 사유가 묻히는데, 이 값들은 진단이 이미 판정해 둔 결론이다.
    private String digestOf(StoryMatchProfile profile) {
        if (profile == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "이별 사유", profile.getReason());
        if (!profile.subReasonList().isEmpty()) {
            append(sb, "세부(앞이 방아쇠)", String.join(", ", profile.subReasonList()));
        }
        append(sb, "헤어지자고 한 쪽", profile.getDumper());
        append(sb, "잘못", profile.getFault());
        append(sb, "지금 연락 상태", profile.getContactState());
        if (profile.getDatingMonths() != null) {
            append(sb, "만난 기간", profile.getDatingMonths() + "개월");
        }
        if (profile.getMonthsSinceBreakup() != null) {
            append(sb, "이별 후 경과", profile.getMonthsSinceBreakup() + "개월");
        }
        append(sb, "나이대", profile.getAgeGroup());
        if (Boolean.TRUE.equals(profile.getPartnerHasNew())) {
            append(sb, "상대에게 새 사람", "있음");
        }
        if (Boolean.TRUE.equals(profile.getRepeatBreakup())) {
            append(sb, "이별과 재회 반복", "있음");
        }
        return sb.toString();
    }

    private void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}
