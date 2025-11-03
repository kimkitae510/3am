package com.threeam.story.repository;

import com.threeam.story.entity.Message;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 커서 페이지네이션: id 역순(최신→과거). Slice는 내부적으로 size+1을 조회해 COUNT 없이 다음 페이지 유무를 판단한다.
    // 첫 페이지(커서 없음)이자, LLM 맥락(최근 N개) 조회용으로도 재사용한다(getContent()).
    Slice<Message> findByStoryIdOrderByIdDesc(Long storyId, Pageable pageable);

    // 위로 스크롤: 커서(마지막으로 로드한 가장 오래된 id)보다 과거만 가져온다.
    Slice<Message> findByStoryIdAndIdLessThanOrderByIdDesc(Long storyId, Long cursor, Pageable pageable);

    // 폴링: 방금 보낸 메시지(after) 이후에 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로 가져온다.
    List<Message> findByStoryIdAndIdGreaterThanOrderByIdAsc(Long storyId, Long after);

    void deleteByStoryId(Long storyId);
}
