package com.threeam.chip;

import com.threeam.story.entity.Message;
import com.threeam.story.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 판별 결과를 유저 메시지 행에 찍는 짧은 트랜잭션.
//
// chip_id와 따로 두는 이유는 측정이다. chip_id는 "유저가 눌렀다"라서 클릭률의 분자고,
// 여기에 추론 결과를 섞으면 그 수를 못 센다. 그리고 둘 다 비어 있는 유저 메시지가 곧
// "칩에 없는 질문"이라, 40개를 늘릴 후보가 이 컬럼 하나로 세어진다.
//
// 삼키는 try/catch는 부르는 쪽(ChipMatcher)에 둔다 — 같은 빈 안에서 감싸면 자기호출이 되어
// @Transactional이 무효가 되고, 값이 예외도 로그도 없이 사라진다(칩 추천에서 겪은 그대로).
@Slf4j
@Service
@RequiredArgsConstructor
public class ChipMatchTxService {

    private final MessageRepository messageRepository;

    @Transactional
    public void save(Long messageId, String chipId) {
        messageRepository.findById(messageId)
                .ifPresent(message -> message.assignMatchedChip(chipId));
    }

    public void saveQuietly(Long messageId, String chipId) {
        try {
            save(messageId, chipId);
        } catch (RuntimeException e) {
            log.warn("자유입력 판별 저장 실패 messageId={} — 모듈 없이 상담한다", messageId, e);
        }
    }
}
