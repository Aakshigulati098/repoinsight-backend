
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.ChatConversation;
import com.repoinsight.repository.entity.ChatMessage;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.ChatConversationRepository;
import com.repoinsight.repository.repository.ChatMessageRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatHistoryService {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_MESSAGE_CONTEXT_CHARS = 1200;
    private static final int MAX_TOTAL_HISTORY_CHARS = 5000;

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final RepositoryChatService repositoryChatService;

    public ChatHistoryService(
            RepositoryInfoRepository repositoryInfoRepository,
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository,
            RepositoryChatService repositoryChatService) {
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.repositoryChatService = repositoryChatService;
    }

    @Transactional
    public Map<String, Object> createChat(Long repositoryId, String question) {
        RepositoryInfo repository = repositoryInfoRepository.findById(repositoryId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Repository not found: " + repositoryId));

        ChatConversation conversation = new ChatConversation();
        conversation.setRepositoryInfo(repository);
        conversation.setTitle(makeTitle(question));
        conversation = conversationRepository.save(conversation);

        // Allow an empty conversation for the frontend's "New Chat" button.
        if (question != null && !question.isBlank()) {
            saveExchange(conversation, question);
        }

        return getChatDetails(conversation);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChats(Long repositoryId) {
        if (!repositoryInfoRepository.existsById(repositoryId)) {
            throw new IllegalArgumentException(
                    "Repository not found: " + repositoryId);
        }

        return conversationRepository
                .findByRepositoryInfo_IdOrderByUpdatedAtDesc(repositoryId)
                .stream()
                .map(this::conversationSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMessages(Long conversationId) {
        ChatConversation conversation = conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Chat not found: " + conversationId));

        return messageRepository
                .findByConversation_IdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(this::messageDto)
                .toList();
    }

    @Transactional
    public Map<String, Object> sendMessage(
            Long conversationId,
            String question) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }

        ChatConversation conversation = conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Chat not found: " + conversationId));

        saveExchange(conversation, question);
        conversationRepository.save(conversation);

        return getChatDetails(conversation);
    }

    @Transactional
    public void deleteChat(Long conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw new IllegalArgumentException(
                    "Chat not found: " + conversationId);
        }

        messageRepository.deleteByConversation_Id(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    private void saveExchange(
            ChatConversation conversation,
            String question) {

        // Read previous messages before saving the current exchange.
        List<ChatMessage> previousMessages =
                messageRepository.findByConversation_IdOrderByCreatedAtAsc(
                        conversation.getId());

        String conversationContext =
                buildConversationContext(previousMessages);

        Map<String, Object> result = repositoryChatService.ask(
                conversation.getRepositoryInfo().getId(),
                question.trim(),
                conversationContext);

        String answer = Objects.toString(result.get("answer"), "");

        Object rawSources = result.get("sources");
        String sources = rawSources instanceof Collection<?> collection
                ? collection.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("\n"))
                : "";

        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversation(conversation);
        userMessage.setRole("user");
        userMessage.setContent(question.trim());
        messageRepository.save(userMessage);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversation(conversation);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(answer);
        assistantMessage.setSourceFiles(sources);
        messageRepository.save(assistantMessage);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    static String buildConversationContext(List<ChatMessage> previousMessages) {
        if (previousMessages == null || previousMessages.isEmpty()) {
            return "";
        }

        int start = Math.max(
                0, previousMessages.size() - MAX_HISTORY_MESSAGES);

        StringBuilder history = new StringBuilder();

        for (int i = start; i < previousMessages.size(); i++) {
            ChatMessage message = previousMessages.get(i);

            if (message == null || message.getContent() == null
                    || message.getContent().isBlank()) {
                continue;
            }

            String role = "assistant".equalsIgnoreCase(message.getRole())
                    ? "Assistant"
                    : "User";

            String content = message.getContent().trim();

            if (content.length() > MAX_MESSAGE_CONTEXT_CHARS) {
                content = content.substring(
                        0, MAX_MESSAGE_CONTEXT_CHARS) + "...";
            }

            String entry = role + ": " + content + "\n";

            if (history.length() + entry.length()
                    > MAX_TOTAL_HISTORY_CHARS) {
                break;
            }

            history.append(entry);
        }

        if (history.isEmpty()) {
            return "";
        }

        return history.toString().trim();
    }

    private Map<String, Object> getChatDetails(
            ChatConversation conversation) {

        Map<String, Object> result =
                new LinkedHashMap<>(conversationSummary(conversation));

        result.put("messages", messageRepository
                .findByConversation_IdOrderByCreatedAtAsc(
                        conversation.getId())
                .stream()
                .map(this::messageDto)
                .toList());

        return result;
    }

    private Map<String, Object> conversationSummary(
            ChatConversation conversation) {

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", conversation.getId());
        dto.put("repositoryId", conversation.getRepositoryInfo().getId());
        dto.put("title", conversation.getTitle());
        dto.put("createdAt", conversation.getCreatedAt());
        dto.put("updatedAt", conversation.getUpdatedAt());

        return dto;
    }

    private Map<String, Object> messageDto(ChatMessage message) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", message.getId());
        dto.put("role", message.getRole());
        dto.put("content", message.getContent());

        dto.put("sources",
                message.getSourceFiles() == null
                        || message.getSourceFiles().isBlank()
                        ? List.of()
                        : Arrays.asList(
                                message.getSourceFiles().split("\\n")));

        dto.put("createdAt", message.getCreatedAt());

        return dto;
    }

    private String makeTitle(String question) {
        if (question == null || question.isBlank()) {
            return "New Chat";
        }

        String normalized = question.trim().replaceAll("\\s+", " ");

        return normalized.length() <= 60
                ? normalized
                : normalized.substring(0, 57) + "...";
    }
}
