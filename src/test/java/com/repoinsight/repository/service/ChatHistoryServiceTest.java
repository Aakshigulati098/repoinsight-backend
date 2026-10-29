package com.repoinsight.repository.service;

import com.repoinsight.repository.repository.ChatConversationRepository;
import com.repoinsight.repository.repository.ChatMessageRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import com.repoinsight.repository.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryServiceTest {

    @Test
    void conversationContextContainsPriorMessagesWithoutEmbeddingLatestQuestion() {
        ChatMessage userMessage = new ChatMessage();
        userMessage.setRole("user");
        userMessage.setContent("Explain the project structure");
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("The repository has Java and Python modules.");

        String context = ChatHistoryService.buildConversationContext(
                List.of(userMessage, assistantMessage));

        assertThat(context)
                .contains("User: Explain the project structure")
                .contains("Assistant: The repository has Java and Python modules.")
                .doesNotContain("Latest question:");
    }

    @Test
    void deleteChatRemovesMessagesBeforeConversation() {
        RepositoryInfoRepository repositoryInfoRepository =
                mock(RepositoryInfoRepository.class);
        ChatConversationRepository conversationRepository =
                mock(ChatConversationRepository.class);
        ChatMessageRepository messageRepository =
                mock(ChatMessageRepository.class);
        ChatHistoryService service = new ChatHistoryService(
                repositoryInfoRepository,
                conversationRepository,
                messageRepository,
                mock(RepositoryChatService.class));
        when(conversationRepository.existsById(12L)).thenReturn(true);

        service.deleteChat(12L);

        InOrder deletionOrder = inOrder(messageRepository, conversationRepository);
        deletionOrder.verify(messageRepository).deleteByConversation_Id(12L);
        deletionOrder.verify(conversationRepository).deleteById(12L);
    }

    @Test
    void deleteChatRejectsUnknownConversation() {
        ChatConversationRepository conversationRepository =
                mock(ChatConversationRepository.class);
        ChatHistoryService service = new ChatHistoryService(
                mock(RepositoryInfoRepository.class),
                conversationRepository,
                mock(ChatMessageRepository.class),
                mock(RepositoryChatService.class));
        when(conversationRepository.existsById(12L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteChat(12L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat not found: 12");
    }
}
