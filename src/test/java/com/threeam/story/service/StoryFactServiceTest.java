package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoryFactServiceTest {

    private static final Long STORY_ID = 10L;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryRepository storyRepository;

    @InjectMocks
    private StoryFactService storyFactService;

    private List<StoryFact> existingFacts(String... facts) {
        List<StoryFact> list = new ArrayList<>();
        for (String fact : facts) {
            list.add(StoryFact.of(STORY_ID, fact, 1L));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<StoryFact> capturedSaveAll() {
        ArgumentCaptor<List<StoryFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(storyFactRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("표현(공백)만 다른 기존 사실은 건너뛰고 새 사실만 출처와 함께 저장한다")
    void appendFacts_skipsKnownFacts() {
        given(storyFactRepository.findByStoryIdOrderByIdAsc(STORY_ID))
                .willReturn(existingFacts("상대가 먼저 이별 통보"));

        storyFactService.appendFacts(STORY_ID, 99L,
                List.of("상대가  먼저   이별 통보", "일주일 전 상대에게서 연락 옴"));

        List<StoryFact> saved = capturedSaveAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getFact()).isEqualTo("일주일 전 상대에게서 연락 옴");
        assertThat(saved.get(0).getSourceAssessmentId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("같은 배치 안에서 중복된 사실은 1건만 저장한다")
    void appendFacts_dedupsWithinBatch() {
        given(storyFactRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of());

        storyFactService.appendFacts(STORY_ID, null,
                List.of("연락이 다시 시작됨", "연락이  다시 시작됨"));

        assertThat(capturedSaveAll()).hasSize(1);
        assertThat(capturedSaveAll().get(0).getSourceAssessmentId()).isNull(); // 채팅 추출 경로
    }

    @Test
    @DisplayName("원장에 상한이 없다 — 많이 쌓인 사연에도 전부 받고 아무것도 지우지 않는다")
    void appendFacts_neverEvicts() {
        String[] facts = new String[120];   // 관측 기준(100)을 이미 넘긴 사연
        for (int i = 0; i < 120; i++) {
            facts[i] = "사실" + (i + 1);
        }
        given(storyFactRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(existingFacts(facts));

        storyFactService.appendFacts(STORY_ID, 99L,
                List.of("새사실1", "새사실2", "새사실3", "새사실4", "새사실5"));

        assertThat(capturedSaveAll()).hasSize(5);
        verify(storyFactRepository, never()).deleteAll(anyList());
        verify(storyFactRepository, never()).delete(any(StoryFact.class));
    }

    @Test
    @DisplayName("정정 기록 - 같은 문장이 이미 있어도 중복 제거 없이 매번 새 줄로 남긴다")
    void appendCorrection_alwaysAppends() {
        storyFactService.appendCorrection(STORY_ID, "유저가 직접 확인함: 사귀는 중이 아니라 헤어진 상태다");

        ArgumentCaptor<StoryFact> captor = ArgumentCaptor.forClass(StoryFact.class);
        verify(storyFactRepository).save(captor.capture());
        assertThat(captor.getValue().getFact()).isEqualTo("유저가 직접 확인함: 사귀는 중이 아니라 헤어진 상태다");
        assertThat(captor.getValue().getSourceAssessmentId()).isNull();
        verify(storyFactRepository, never()).findByStoryIdOrderByIdAsc(any()); // 기존 원장과 비교하지 않는다
    }

    @Test
    @DisplayName("직접 입력 사실 - 소유 확인 후 USER 출처로 다듬어 저장한다")
    void appendUserFact_savesWithUserSource() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(mock(Story.class)));
        given(storyFactRepository.save(any(StoryFact.class)))
                .willAnswer(inv -> inv.getArgument(0));

        storyFactService.appendUserFact(1L, STORY_ID, "  상대에게 어제 먼저 연락이 옴  ");

        ArgumentCaptor<StoryFact> captor = ArgumentCaptor.forClass(StoryFact.class);
        verify(storyFactRepository).save(captor.capture());
        assertThat(captor.getValue().getFact()).isEqualTo("상대에게 어제 먼저 연락이 옴");
        assertThat(captor.getValue().getSource()).isEqualTo(FactSource.USER);
        assertThat(captor.getValue().getSourceAssessmentId()).isNull();
    }

    @Test
    @DisplayName("직접 입력 취소 - 내 사연의 USER 출처 사실만 지운다")
    void deleteUserFact_deletesOwnUserFact() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(mock(Story.class)));
        StoryFact fact = StoryFact.userProvided(STORY_ID, "지울 사실");
        given(storyFactRepository.findById(5L)).willReturn(Optional.of(fact));

        storyFactService.deleteUserFact(1L, STORY_ID, 5L);

        verify(storyFactRepository).delete(fact);
    }

    @Test
    @DisplayName("직접 입력 취소 - 추출된 사실은 지울 수 없다")
    void deleteUserFact_rejectsExtractedFact() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(mock(Story.class)));
        given(storyFactRepository.findById(5L))
                .willReturn(Optional.of(StoryFact.of(STORY_ID, "추출된 사실", 99L)));

        assertThatThrownBy(() -> storyFactService.deleteUserFact(1L, STORY_ID, 5L))
                .isInstanceOf(BusinessException.class);
        verify(storyFactRepository, never()).delete(any(StoryFact.class));
    }

    @Test
    @DisplayName("직접 입력 취소 - 다른 사연의 사실은 지울 수 없다")
    void deleteUserFact_rejectsForeignStoryFact() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(mock(Story.class)));
        given(storyFactRepository.findById(5L))
                .willReturn(Optional.of(StoryFact.userProvided(999L, "남의 사실")));

        assertThatThrownBy(() -> storyFactService.deleteUserFact(1L, STORY_ID, 5L))
                .isInstanceOf(BusinessException.class);
        verify(storyFactRepository, never()).delete(any(StoryFact.class));
    }

    @Test
    @DisplayName("직접 입력 사실 - 남의 사연이면 저장 없이 거부한다")
    void appendUserFact_rejectsUnownedStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> storyFactService.appendUserFact(1L, STORY_ID, "사실"))
                .isInstanceOf(BusinessException.class);
        verify(storyFactRepository, never()).save(any(StoryFact.class));
    }

    @Test
    @DisplayName("새 사실이 없으면(빈 목록/null) 원장을 조회조차 하지 않는다")
    void appendFacts_skipsWhenEmpty() {
        storyFactService.appendFacts(STORY_ID, 99L, List.of());
        storyFactService.appendFacts(STORY_ID, 99L, null);

        verifyNoInteractions(storyFactRepository);
    }
}
