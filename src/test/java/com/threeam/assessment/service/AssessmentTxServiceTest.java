package com.threeam.assessment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentTxServiceTest {

    private static final Long STORY_ID = 10L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private AssessmentRepository assessmentRepository;

    @InjectMocks
    private AssessmentTxService txService;

    private Assessment savedAssessment(Long id) {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(20)
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "id", id);
        return assessment;
    }

    @Test
    @DisplayName("진단 저장 - 새 사실을 저장된 진단의 id(출처)와 함께 원장 서비스로 넘긴다")
    void save_delegatesFactsWithAssessmentId() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));
        List<String> newFacts = List.of("일주일 전 상대에게서 연락 옴");

        txService.save(STORY_ID, savedAssessment(null), null, newFacts);

        verify(storyFactService).appendFacts(STORY_ID, 99L, newFacts);
    }

    @Test
    @DisplayName("진단 저장 - 요약이 비어 있으면 기억(감정 요약)을 건드리지 않는다")
    void save_skipsMemoryWhenSummaryBlank() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));

        txService.save(STORY_ID, savedAssessment(null), "  ", null);

        verifyNoInteractions(storyMemoryRepository);
    }
}
