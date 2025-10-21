package com.threeam.conversation.repository;

import com.threeam.conversation.entity.Message;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationId(Long conversationId, Pageable pageable);

    // LLM에 넘길 직전 맥락(최근 N턴)을 뽑을 때 사용. 최신순으로 N개만 가져와 호출부에서 시간순으로 뒤집는다.
    List<Message> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    void deleteByConversationId(Long conversationId);
}
