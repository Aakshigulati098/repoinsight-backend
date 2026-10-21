package com.repoinsight.repository.repository;

import com.repoinsight.repository.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversation_IdOrderByCreatedAtAsc(Long conversationId);
}
