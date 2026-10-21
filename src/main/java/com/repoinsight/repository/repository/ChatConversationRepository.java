package com.repoinsight.repository.repository;

import com.repoinsight.repository.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {
    List<ChatConversation> findByRepositoryInfo_IdOrderByUpdatedAtDesc(Long repositoryId);
    Optional<ChatConversation> findByIdAndRepositoryInfo_Id(Long id, Long repositoryId);
}
