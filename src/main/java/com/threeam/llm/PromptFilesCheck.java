package com.threeam.llm;

import com.threeam.assessment.AssessmentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 프롬프트 실문구 4종은 전부 저장소 밖 yml(gitignore)에서 optional로 주입된다.
// optional이라 파일이 없어도 기동은 성공하고, 그때 서비스는 자리표시자나 빈 값으로 조용히 돈다 —
// 에러도 예외도 없이 답변 품질만 떨어져서 원인 찾기가 가장 어려운 종류의 고장이 된다.
// 새 개발 머신, 배포 서버에 파일을 안 올린 경우가 실제 시나리오라 기동 시점에 한 번 확인한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptFilesCheck {

    private final ChatPersonaProperties chatProperties;
    private final FactExtractionProperties extractionProperties;
    private final AssessmentProperties assessmentProperties;

    @PostConstruct
    public void warnOnMissingPrompts() {
        // 페르소나와 루브릭은 자리표시자 기본값이 있어 '비었는지'로는 못 가른다 — 길이로 본다.
        // 실문구는 수천 자 단위라 이 기준에 걸리면 파일이 안 읽힌 것이 확실하다.
        warnIfPlaceholder("persona.yml", "llm.chat.persona", chatProperties.getPersona());
        warnIfPlaceholder("extractor.yml", "llm.extraction.prompt", extractionProperties.getPrompt());
        warnIfPlaceholder("rubric.yml", "llm.assessment.rubric", assessmentProperties.getRubric());
        warnIfBlank("reminder.yml", "llm.chat.reminder", chatProperties.getReminder());
        warnIfBlank("reminder.yml", "llm.chat.ending-reminder", chatProperties.getEndingReminder());
    }

    // 실문구로 보기엔 너무 짧은 길이. 자리표시자 기본값은 전부 이 아래다.
    private static final int PLACEHOLDER_MAX_LENGTH = 500;

    private void warnIfPlaceholder(String file, String key, String value) {
        if (value == null || value.strip().length() < PLACEHOLDER_MAX_LENGTH) {
            log.warn("프롬프트 미주입: {}({})가 자리표시자 수준이다 — {}이(가) 없거나 안 읽혔다. "
                    + "서비스는 돌지만 품질 규칙이 빠진 상태다.", key, length(value), file);
        }
    }

    private void warnIfBlank(String file, String key, String value) {
        if (value == null || value.isBlank()) {
            log.warn("프롬프트 미주입: {}가 비었다 — {}이(가) 없거나 안 읽혔다. 해당 지시는 주입 자체를 건너뛴다.",
                    key, file);
        }
    }

    private String length(String value) {
        return (value == null ? 0 : value.strip().length()) + "자";
    }
}
