package com.repoinsight.repository.controller;

import com.repoinsight.repository.service.ChatHistoryService;
import com.repoinsight.repository.service.RepositoryChatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryIndexingController {

    private final RepositoryChatService chatService;
    private final ChatHistoryService chatHistoryService;

    public RepositoryIndexingController(
            RepositoryChatService chatService,
            ChatHistoryService chatHistoryService) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
    }

    // Existing endpoint retained for backward compatibility.
    @PostMapping("/{id}/chat")
    public Map<String, Object> chat(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }
        return chatService.ask(id, question);
    }

    // Create a new conversation and ask its first question.
    @PostMapping("/{id}/chats")
    public Map<String, Object> createChat(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> request) {
        return chatHistoryService.createChat(id, request.get("question"));
    }

    // List conversation history for a repository.
    @GetMapping("/{id}/chats")
    public List<Map<String, Object>> listChats(@PathVariable("id") Long id) {
        return chatHistoryService.listChats(id);
    }
}