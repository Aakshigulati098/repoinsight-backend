package com.repoinsight.repository.controller;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.service.ChatHistoryService;
import com.repoinsight.repository.service.RepositoryChatService;
import com.repoinsight.repository.service.RepositoryIndexService;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryIndexingController {

    private final RepositoryChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final RepositoryIndexService repositoryIndexService;
    private final RepositoryInfoRepository repositoryInfoRepository;

    public RepositoryIndexingController(
            RepositoryChatService chatService,
            ChatHistoryService chatHistoryService,
            RepositoryIndexService repositoryIndexService,
            RepositoryInfoRepository repositoryInfoRepository) {

        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.repositoryIndexService = repositoryIndexService;
        this.repositoryInfoRepository = repositoryInfoRepository;
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

    @PostMapping("/{id}/index")
    public ResponseEntity<Map<String, Object>> indexRepository(
            @PathVariable("id") Long id) {

        repositoryIndexService.indexRepositoryAsync(id);

        return ResponseEntity.accepted().body(Map.of(
                "repositoryId", id,
                "status", "IN_PROGRESS",
                "message", "Repository indexing has been started"
        ));
    }


    @GetMapping("/{id}/index/status")
    public Map<String, Object> getIndexingStatus(
            @PathVariable("id") Long id) {

        RepositoryInfo repository = repositoryInfoRepository
                .findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Repository not found: " + id));

        Map<String, Object> response = new HashMap<>();
        response.put("repositoryId", repository.getId());
        response.put("status", repository.getIndexingStatus());
        response.put("progress", repository.getIndexingProgress());
        response.put("chunksIndexed", repository.getIndexedChunks());
        response.put("startedAt", repository.getIndexingStartedAt());
        response.put("completedAt", repository.getIndexingCompletedAt());
        response.put("error", repository.getIndexingError());

        return response;
    }
}