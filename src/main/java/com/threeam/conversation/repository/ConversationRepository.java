package com.threeam.conversation.repository;

import com.threeam.conversation.entity.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

    // 소유권까지 한 번에 건다. 없거나 남의 것이면 빈 Optional → 404로 통일(존재 여부 노출 방지).
    Optional<Conversation> findByIdAndUserId(Long id, Long userId);
}
